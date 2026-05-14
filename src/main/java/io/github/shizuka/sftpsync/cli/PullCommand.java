package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.diff.ChangeSet;
import io.github.shizuka.sftpsync.diff.ThreeWayDiffer;
import io.github.shizuka.sftpsync.manifest.BaseStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestBuilder;
import io.github.shizuka.sftpsync.manifest.ManifestEntry;
import io.github.shizuka.sftpsync.manifest.ScanCache;
import io.github.shizuka.sftpsync.manifest.ScanCacheStore;
import io.github.shizuka.sftpsync.sftp.RemoteManifestStore;
import io.github.shizuka.sftpsync.sftp.RemoteTransfer;
import io.github.shizuka.sftpsync.sftp.SftpSession;
import io.github.shizuka.sftpsync.sftp.SftpSession.HostKeyMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Baja cambios remotos al disco local, aplicando el ChangeSet del three-way diff.
 *
 * <p>Flujo (docs/design.md §6.4):
 * <ol>
 *   <li>Scan local + bajar manifest remoto + cargar base.</li>
 *   <li>Diff.</li>
 *   <li>Conflictos: dejar local intacto y bajar la versión remota como
 *       {@code <path>.remote}. Reportar.</li>
 *   <li>No-conflicto (toDownload): bajar a {@code .sync/tmp/<uuid>}, verificar
 *       sha256 contra manifest_remote, {@code Files.move(ATOMIC_MOVE)} a destino.</li>
 *   <li>Deletes locales (toDeleteLocal): borrar del disco.</li>
 *   <li>Actualizar {@code base.json} solo si TODO el pull fue exitoso (sin
 *       conflictos pendientes y sin failures de descarga).</li>
 * </ol>
 *
 * <p><b>Paralelismo:</b> las descargas corren en paralelo en un pool de N
 * worker threads (default 8, configurable con {@code --workers}). Cada worker
 * tiene su propia {@link SftpSession} (MINA SSHD requiere un {@code SftpClient}
 * por thread). Las sesiones del pool se abren al arrancar el pull y se cierran
 * en {@code finally}. La sesión principal queda para manifest fetch + delete-local.
 *
 * <p><b>Failure-mode invariant:</b> si CUALQUIER descarga falla (transient
 * network, hash mismatch, remote file missing, etc.), {@code base.json} NO se
 * actualiza. El próximo pull reintentará todo. Esta política favorece simplicidad
 * sobre recovery parcial — un partial update de base ahorraría re-trabajo pero
 * complica la lógica de three-way diff. Si querés que cambie, primero ajustá
 * los tests y mirá {@code ThreeWayDiffer}.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — pull exitoso (o nada que bajar).</li>
 *   <li>{@code 1/2/3/5} — config / auth / SSH / I/O.</li>
 *   <li>{@code 7} — hay conflictos sin resolver tras el pull.</li>
 * </ul>
 */
@Command(
    name = "pull",
    description = "Bajar cambios remotos. Conflictos se marcan, no se aplican."
)
public final class PullCommand implements Callable<Integer> {

    private static final int MAX_WORKERS = 32;
    private static final int DEFAULT_WORKERS = 8;

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure", description = "Aceptar cualquier host key.")
    boolean insecure;

    @Option(names = "--no-cache", description = "Forzar rehash local.")
    boolean noCache;

    @Option(names = "--workers",
            description = "Descargas paralelas. Default ${DEFAULT-VALUE}, max " + MAX_WORKERS + ".",
            defaultValue = "" + DEFAULT_WORKERS)
    int workers;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        if (workers < 1 || workers > MAX_WORKERS) {
            err.println("--workers debe estar entre 1 y " + MAX_WORKERS + ". Recibido: " + workers);
            return 2;
        }

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

        // Limpieza inicial de .sync/tmp/: borrar UUIDs que sobraron de un
        // crash anterior. Es seguro porque nadie debería tener handles abiertos
        // a esos paths.
        sweepTmpDir(root);

        // --- Scan local ---
        ScanCache cache = noCache ? new ScanCache() : ScanCacheStore.loadOrEmpty(root);
        Manifest localManifest;
        try {
            localManifest = new ManifestBuilder(root, config, cache).build();
            ScanCacheStore.save(root, cache);
        } catch (IOException e) {
            err.println("Error escaneando: " + e.getMessage());
            return 5;
        }

        HostKeyMode mode = insecure ? HostKeyMode.INSECURE : HostKeyMode.STRICT;
        String remoteRoot = config.remote().remoteRoot();

        try (SftpSession primary = SftpSession.open(config.remote(), mode)) {
            Manifest remoteManifest = RemoteManifestStore.loadOrEmpty(
                primary.sftp(), remoteRoot, config.clientId());
            if (remoteManifest.entries().isEmpty()
                && !RemoteManifestStore.exists(primary.sftp(), remoteRoot)) {
                out.println("No hay manifest remoto — nada que pullear.");
                return 0;
            }

            Manifest baseManifest = BaseStore.loadOrEmpty(root);
            ChangeSet cs = ThreeWayDiffer.diff(baseManifest, localManifest, remoteManifest);

            if (cs.toDownload().isEmpty() && cs.toDeleteLocal().isEmpty()
                && cs.conflicts().isEmpty()) {
                out.println("Nada que pullear.");
                return 0;
            }

            // Construir la worklist de descargas paralelas.
            List<DownloadTask> tasks = new ArrayList<>();
            List<DownloadResult> noopConflicts = new ArrayList<>();
            for (String relPath : cs.conflicts()) {
                ManifestEntry remoteEntry = remoteManifest.entries().get(relPath);
                if (remoteEntry == null) {
                    // Conflicto por borrado remoto vs cambio local: no hay nada que bajar.
                    noopConflicts.add(new DownloadResult(
                        relPath, true, "conflict ", "remoto borrado, local editado"));
                    continue;
                }
                tasks.add(new DownloadTask(
                    relPath, remoteEntry, root.resolve(relPath + ".remote"), true));
            }
            for (String relPath : cs.toDownload()) {
                ManifestEntry remoteEntry = remoteManifest.entries().get(relPath);
                tasks.add(new DownloadTask(
                    relPath, remoteEntry, root.resolve(relPath), false));
            }

            // Ejecutar descargas (paralelo si N > 1 y hay tasks).
            List<DownloadResult> results = tasks.isEmpty()
                ? List.of()
                : runDownloads(tasks, primary, config.remote(), mode, remoteRoot,
                    root, workers, err);

            // Output ordenado: primero conflicts no-op, luego results de descargas, ambos por relPath.
            List<DownloadResult> all = new ArrayList<>(noopConflicts.size() + results.size());
            all.addAll(noopConflicts);
            all.addAll(results);
            all.sort(Comparator.comparing(DownloadResult::relPath));

            int failed = 0;
            for (DownloadResult r : all) {
                if (!r.ok()) {
                    failed++;
                    err.println("error     " + r.relPath() + ": " + r.detail());
                } else if ("conflict ".equals(r.action())) {
                    err.println("conflict  " + r.relPath()
                        + (r.detail() != null ? " (" + r.detail() + ")" : " → " + r.relPath() + ".remote"));
                } else {
                    out.println(r.action() + "  " + r.relPath());
                }
            }

            // Deletes locales (secuencial, sin red).
            for (String relPath : cs.toDeleteLocal()) {
                Path target = root.resolve(relPath);
                Files.deleteIfExists(target);
                out.println("delete    " + relPath);
            }

            // Limpieza final de .sync/tmp/ (residuos de fallos).
            sweepTmpDir(root);

            if (failed > 0) {
                err.println();
                err.println("Pull con " + failed
                    + " fallo(s): base.json NO actualizado. Reintentá.");
                return 5;
            }
            if (cs.hasConflicts()) {
                err.println();
                err.println("Pull con conflictos: base.json NO actualizado.");
                err.println("Resolvé los conflictos (mirar archivos *.remote) y reintentá.");
                return 7;
            }
            BaseStore.save(root, remoteManifest);

            out.println();
            out.println("Pull OK: "
                + (results.size() - failed) + " bajados, "
                + cs.toDeleteLocal().size() + " borrados.");
            return 0;
        } catch (IOException e) {
            return SftpErrors.mapToExitCode(e, err);
        }
    }

    /**
     * Ejecuta las descargas en paralelo sobre un pool de hasta N sesiones SFTP.
     *
     * <p>El pool <b>reutiliza la sesión principal</b> (la que ya está abierta para
     * fetch del manifest y operaciones secuenciales) y abre hasta {@code N - 1}
     * sesiones adicionales. Si el servidor rechaza alguna de las adicionales (ej.
     * MaxStartups / MaxSessions de OpenSSH), degrada el pool al tamaño efectivo
     * y emite un warning a stderr — el pull SIGUE funcionando, solo con menos
     * paralelismo. Mínimo garantizado: 1 worker (la primary).
     *
     * <p>Las sesiones extras se cierran en {@code finally}. La primary la cierra
     * el caller via try-with-resources, NO esta función.
     *
     * <p><b>Nota de thread-safety:</b> {@code SftpClient} de MINA NO es
     * thread-safe; por eso cada worker necesita una sesión propia. La primary
     * está IDLE mientras los workers corren (su único uso post-runDownloads son
     * los deletes locales, que son operaciones de filesystem, no SFTP), por lo
     * que reusarla acá es seguro.
     */
    private static List<DownloadResult> runDownloads(
            List<DownloadTask> tasks, SftpSession primary, RemoteConfig remote,
            HostKeyMode mode, String remoteRoot, Path root,
            int workersRequested, PrintWriter err) {
        int requested = Math.min(workersRequested, tasks.size());
        int poolCapacity = Math.max(requested, 1);

        BlockingQueue<SftpSession> pool = new ArrayBlockingQueue<>(poolCapacity);
        pool.add(primary);
        List<SftpSession> extras = new ArrayList<>(Math.max(0, requested - 1));
        for (int i = 1; i < requested; i++) {
            try {
                SftpSession s = SftpSession.open(remote, mode);
                extras.add(s);
                pool.add(s);
            } catch (IOException e) {
                err.println("warning: solo " + (1 + extras.size())
                    + " de " + requested + " sesiones SFTP abrieron OK ("
                    + e.getMessage() + "). Continuando con menos workers.");
                break;
            }
        }
        int actualWorkers = pool.size();

        ExecutorService exec = Executors.newFixedThreadPool(actualWorkers, r -> {
            Thread t = new Thread(r, "pull-worker");
            t.setDaemon(true);
            return t;
        });
        List<Future<DownloadResult>> futures = new ArrayList<>(tasks.size());
        try {
            for (DownloadTask task : tasks) {
                futures.add(exec.submit(() -> runOneTask(task, pool, remoteRoot, root)));
            }
            List<DownloadResult> results = new ArrayList<>(tasks.size());
            for (Future<DownloadResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    results.add(new DownloadResult(
                        "(interrupted)", false, null, "executor interrupted"));
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    results.add(new DownloadResult(
                        "(unknown)", false, null, "executor: " + cause.getMessage()));
                }
            }
            return results;
        } finally {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
            // SOLO cerramos las sesiones extras. La primary la cierra el caller
            // via try-with-resources.
            for (SftpSession s : extras) closeQuietly(s);
        }
    }

    private static DownloadResult runOneTask(
            DownloadTask task, BlockingQueue<SftpSession> pool,
            String remoteRoot, Path root) {
        SftpSession session = null;
        Path tmp = null;
        try {
            session = pool.take();
            tmp = stagedTmp(root, task.entry().sha256());
            downloadAndMove(session, remoteRoot, task.relPath(), task.entry(),
                tmp, task.target());
            return new DownloadResult(
                task.relPath(), true,
                task.isConflict() ? "conflict " : "download", null);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new DownloadResult(task.relPath(), false, null, "interrupted");
        } catch (IOException e) {
            return new DownloadResult(task.relPath(), false, null, e.getMessage());
        } finally {
            // downloadAndMove ya limpia tmp en sus propios catch; esto es solo
            // defensa en profundidad para que no quede basura si pasó algo raro.
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException _) {}
            }
            if (session != null) pool.offer(session);
        }
    }

    /** Path local temporal en {@code .sync/tmp/<uuid>-<shaPrefix>}. */
    private static Path stagedTmp(Path root, String sha256) throws IOException {
        Path tmpDir = root.resolve(SyncConfigStore.DIR_NAME).resolve("tmp");
        Files.createDirectories(tmpDir);
        String suffix = sha256 != null && sha256.length() >= 8 ? sha256.substring(0, 8) : "x";
        return tmpDir.resolve(UUID.randomUUID() + "-" + suffix);
    }

    /**
     * Borra archivos huérfanos en {@code .sync/tmp/}. Se llama al inicio (limpia
     * residuos de runs previos que crashearon) y al final (limpia residuos del
     * run actual). Idempotente y silencioso: errores se ignoran.
     */
    private static void sweepTmpDir(Path root) {
        Path tmpDir = root.resolve(SyncConfigStore.DIR_NAME).resolve("tmp");
        if (!Files.isDirectory(tmpDir)) return;
        try (Stream<Path> stream = Files.list(tmpDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException _) {}
            });
        } catch (IOException _) {
            // Silencioso: si no podemos limpiar tmp, no es fatal.
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception _) {}
    }

    /**
     * Descarga {@code <remoteRoot>/<relPath>} a {@code tmp}, verifica que el hash
     * coincida con {@code expected.sha256()}, y mueve a {@code finalTarget} con
     * {@code ATOMIC_MOVE}. Si el hash no coincide, borra el tmp y tira IOException.
     *
     * <p>El SHA-256 se calcula en streaming durante la descarga vía {@link
     * RemoteTransfer#downloadAndHash} — sin segunda lectura del disco.
     */
    private static void downloadAndMove(SftpSession session, String remoteRoot,
                                        String relPath, ManifestEntry expected,
                                        Path tmp, Path finalTarget) throws IOException {
        String remotePath = RemoteManifestStore.joinRemote(remoteRoot, relPath);
        try {
            String actualHash = RemoteTransfer.downloadAndHash(session.sftp(), remotePath, tmp);
            if (!actualHash.equals(expected.sha256())) {
                Files.deleteIfExists(tmp);
                throw new IOException("Hash mismatch en " + relPath
                    + ": esperado " + expected.sha256()
                    + ", obtenido " + actualHash);
            }
            Files.createDirectories(finalTarget.getParent());
            try {
                Files.move(tmp, finalTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, finalTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Una descarga pendiente. */
    private record DownloadTask(
        String relPath,
        ManifestEntry entry,
        Path target,
        boolean isConflict
    ) {}

    /**
     * Resultado de una descarga (o de un conflicto no-op).
     *
     * @param relPath ruta relativa del archivo.
     * @param ok      true si la operación terminó OK.
     * @param action  "download" / "conflict " si ok, null si falló. Solo para output.
     * @param detail  mensaje extra (motivo del conflicto, o error si {@code !ok}).
     */
    private record DownloadResult(
        String relPath,
        boolean ok,
        String action,
        String detail
    ) {}
}
