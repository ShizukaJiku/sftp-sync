package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.diff.ChangeSet;
import io.github.shizuka.sftpsync.diff.ThreeWayDiffer;
import io.github.shizuka.sftpsync.manifest.BaseStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestBuilder;
import io.github.shizuka.sftpsync.manifest.ScanCache;
import io.github.shizuka.sftpsync.manifest.ScanCacheStore;
import io.github.shizuka.sftpsync.sftp.RemoteManifestStore;
import io.github.shizuka.sftpsync.sftp.SftpSession;
import io.github.shizuka.sftpsync.sftp.SftpSession.HostKeyMode;
import io.github.shizuka.sftpsync.watcher.StateStore;
import io.github.shizuka.sftpsync.watcher.WatchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Callable;

/**
 * Proceso de fondo que mantiene {@code .sync/state.json} fresco para que
 * {@code status} sea instantáneo.
 *
 * <p><b>NO sincroniza automáticamente.</b> Solo escanea local + baja manifest
 * remoto periódicamente, recalcula el {@link ChangeSet} y persiste el resumen.
 * El usuario sigue siendo quien decide cuándo hacer push/pull.
 *
 * <p>Dos loops sobre un {@link ScheduledExecutorService} con virtual threads:
 * <ul>
 *   <li><b>Local</b>: rescan + write state cada {@code debounceMs * 2} ms (default 1 s).
 *       Aprovecha {@code scancache} para que sea barato.</li>
 *   <li><b>Remote</b>: baja {@code .sync/manifest.json} cada {@code pollIntervalSeconds}
 *       sobre la conexión SFTP persistente.</li>
 * </ul>
 *
 * <p>Shutdown: {@code SIGINT/SIGTERM} → cierra SFTP, drena los loops, sale con 0.
 */
@Command(
    name = "watch",
    description = "Vigilar local y remoto, mantener el status fresco. No sincroniza."
)
public final class WatchCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(WatchCommand.class);

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure", description = "Aceptar cualquier host key.")
    boolean insecure;

    @Option(names = "--poll-interval",
            description = "Override del intervalo de poll del remoto (segundos).")
    Integer pollIntervalOverride;

    @Option(names = "--once",
            description = "Correr un único ciclo local+remoto y salir. Útil para tests/scripts.")
    boolean once;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        Path root = Path.of(parent.directory).toAbsolutePath().normalize();

        SyncConfig config;
        try {
            config = SyncConfigStore.load(root);
        } catch (NoSuchFileException e) {
            err.println("No hay .sync/config.json en " + root);
            err.println("Corré 'sftp-sync init' primero.");
            return 1;
        } catch (IOException e) {
            err.println("Error leyendo config: " + e.getMessage());
            return 1;
        }

        int pollIntervalSeconds = pollIntervalOverride != null
            ? pollIntervalOverride
            : config.watch().pollIntervalSeconds();
        int localPeriodMs = Math.max(500, config.watch().debounceMs() * 2);
        HostKeyMode mode = insecure ? HostKeyMode.INSECURE : HostKeyMode.STRICT;

        try (SftpSession session = SftpSession.open(config.remote(), mode)) {
            WatchLoop loop = new WatchLoop(root, config, session);

            if (once) {
                loop.tickLocal();
                loop.tickRemote();
                loop.publish();
                out.println("Watch once: state.json actualizado en " + StateStore.path(root));
                return 0;
            }

            out.println("Watch corriendo. Local rescan cada " + localPeriodMs + " ms, "
                + "remote poll cada " + pollIntervalSeconds + " s.");
            out.println("Ctrl-C para salir.");
            out.flush();

            // Single-thread scheduler: tickLocal, tickRemote y publish comparten el
            // mismo SFTPClient — corriendo en serie evitamos races del wire protocol
            // de sshj (que no es threadsafe para open/write concurrentes).
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("watch-", 0).factory());

            CountDownLatch shutdownLatch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutdown solicitado, cerrando watcher.");
                shutdownLatch.countDown();
            }, "watch-shutdown"));

            scheduler.scheduleAtFixedRate(loop::tickLocal,
                0, localPeriodMs, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(loop::tickRemote,
                0, pollIntervalSeconds, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(loop::publish,
                500, localPeriodMs, TimeUnit.MILLISECONDS);

            try {
                shutdownLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;

        } catch (IOException e) {
            err.println("Error de conexión inicial: " + e.getMessage());
            return 5;
        }
    }

    /**
     * Estado compartido entre los dos loops + lógica de publicación atómica.
     * Los ticks llenan {@code lastLocal} y {@code lastRemote}; {@code publish}
     * arma el {@link WatchState} y lo escribe.
     */
    private static final class WatchLoop {
        private final Path root;
        private final SyncConfig config;
        private final SftpSession session;
        private final ReentrantLock lock = new ReentrantLock();

        private final AtomicReference<Manifest> lastLocal = new AtomicReference<>();
        private final AtomicReference<Manifest> lastRemote = new AtomicReference<>();
        private final AtomicReference<String> lastLocalScanAt = new AtomicReference<>("");
        private final AtomicReference<String> lastRemoteCheckAt = new AtomicReference<>("");
        private final AtomicBoolean remoteReachable = new AtomicBoolean(true);
        private final AtomicReference<WatchState> lastPublished = new AtomicReference<>();
        private final Deque<String> recentErrors = new ArrayDeque<>();

        WatchLoop(Path root, SyncConfig config, SftpSession session) {
            this.root = root;
            this.config = config;
            this.session = session;
        }

        void tickLocal() {
            try {
                ScanCache cache = ScanCacheStore.loadOrEmpty(root);
                Manifest m = new ManifestBuilder(root, config, cache).build();
                ScanCacheStore.save(root, cache);
                lastLocal.set(m);
                lastLocalScanAt.set(Instant.now().toString());
            } catch (IOException e) {
                appendError("local scan: " + e.getMessage());
            }
        }

        void tickRemote() {
            try {
                Manifest m = RemoteManifestStore.loadOrEmpty(
                    session.sftp(), config.remote().remoteRoot(), config.clientId());
                lastRemote.set(m);
                lastRemoteCheckAt.set(Instant.now().toString());
                remoteReachable.set(true);
            } catch (IOException e) {
                remoteReachable.set(false);
                appendError("remote poll: " + e.getMessage());
            }
        }

        void publish() {
            Manifest local = lastLocal.get();
            Manifest remote = lastRemote.get();
            if (local == null || remote == null) return; // todavía no tenemos ambos

            Manifest base;
            try {
                base = BaseStore.loadOrEmpty(root);
            } catch (IOException e) {
                appendError("base load: " + e.getMessage());
                return;
            }
            ChangeSet cs = ThreeWayDiffer.diff(base, local, remote);

            WatchState state = new WatchState(
                lastRemoteCheckAt.get(),
                lastLocalScanAt.get(),
                new WatchState.Summary(
                    cs.toUpload().size() + cs.toDeleteRemote().size(),
                    cs.toDownload().size() + cs.toDeleteLocal().size(),
                    cs.conflicts().size()),
                remoteReachable.get(),
                snapshotErrors());

            // Si nada cambió desde el último publish — salvo los timestamps —
            // saltearse el write para no desgastar el SSD en idle.
            WatchState prev = lastPublished.get();
            if (prev != null && sameContent(prev, state)) return;
            try {
                StateStore.save(root, state);
                lastPublished.set(state);
            } catch (IOException e) {
                LOG.warn("No pude escribir state.json: {}", e.getMessage());
            }
        }

        /** Compara dos {@link WatchState}s ignorando los timestamps (que siempre cambian). */
        private static boolean sameContent(WatchState a, WatchState b) {
            return a.summary().equals(b.summary())
                && a.remoteReachable() == b.remoteReachable()
                && a.errors().equals(b.errors());
        }

        private void appendError(String msg) {
            lock.lock();
            try {
                recentErrors.addLast(Instant.now() + " " + msg);
                while (recentErrors.size() > 10) recentErrors.pollFirst();
            } finally {
                lock.unlock();
            }
        }

        private List<String> snapshotErrors() {
            lock.lock();
            try {
                return List.copyOf(recentErrors);
            } finally {
                lock.unlock();
            }
        }
    }
}
