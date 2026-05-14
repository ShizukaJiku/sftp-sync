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
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Muestra el delta entre local, remoto y la base del último sync.
 *
 * <p>Flujo:
 * <ol>
 *   <li>Scan local (reusando {@code scancache} si está disponible) → manifest_local.</li>
 *   <li>Bajar manifest remoto → manifest_remote (o vacío si no existe aún).</li>
 *   <li>Cargar {@code .sync/base.json} → manifest_base (o vacío si nunca sincronizamos).</li>
 *   <li>{@link ThreeWayDiffer#diff} → {@link ChangeSet}.</li>
 *   <li>Imprimir resumen + paths agrupados por acción.</li>
 * </ol>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — sync limpio (no hay cambios pendientes ni conflictos).</li>
 *   <li>{@code 1} — no hay config o no se puede leer.</li>
 *   <li>{@code 2} — autenticación falló.</li>
 *   <li>{@code 3} — error SSH/transporte.</li>
 *   <li>{@code 5} — error de I/O.</li>
 *   <li>{@code 7} — hay cambios pendientes (push/pull/conflict) — útil para scripts.</li>
 * </ul>
 */
@Command(
    name = "status",
    description = "Mostrar qué cambió localmente, remotamente, y conflictos pendientes."
)
public final class StatusCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure",
            description = "Aceptar cualquier host key (skip known_hosts).")
    boolean insecure;

    @Option(names = "--no-cache",
            description = "Forzar rehash local, ignorar el scancache.")
    boolean noCache;

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

        // --- 0. Si hay state.json fresco del watcher, lo usamos (instantáneo). ---
        if (!noCache) {
            WatchState fresh = StateStore.loadIfFresh(root, config.watch().pollIntervalSeconds());
            if (fresh != null) {
                printFromState(out, fresh);
                int pending = fresh.summary().localChanged()
                    + fresh.summary().remoteChanged()
                    + fresh.summary().conflicts();
                return pending == 0 ? 0 : 7;
            }
        }

        // --- 1. Scan local ---
        ScanCache cache = noCache ? new ScanCache() : ScanCacheStore.loadOrEmpty(root);
        Manifest localManifest;
        try {
            localManifest = new ManifestBuilder(root, config, cache).build();
            ScanCacheStore.save(root, cache);
        } catch (IOException e) {
            err.println("Error escaneando: " + e.getMessage());
            return 5;
        }

        // --- 2. Bajar manifest remoto ---
        HostKeyMode mode = insecure ? HostKeyMode.INSECURE : HostKeyMode.STRICT;
        Manifest remoteManifest;
        try (SftpSession session = SftpSession.open(config.remote(), mode)) {
            remoteManifest = RemoteManifestStore.loadOrEmpty(
                session.sftp(), config.remote().remoteRoot(), config.clientId());
        } catch (IOException e) {
            return SftpErrors.mapToExitCode(e, err);
        }

        // --- 3. Cargar base ---
        Manifest baseManifest;
        try {
            baseManifest = BaseStore.loadOrEmpty(root);
        } catch (IOException e) {
            err.println("Error leyendo base.json: " + e.getMessage());
            return 5;
        }

        // --- 4. Diff ---
        ChangeSet cs = ThreeWayDiffer.diff(baseManifest, localManifest, remoteManifest);

        // --- 5. Imprimir ---
        printSummary(out, cs);
        return cs.isClean() ? 0 : 7;
    }

    private static void printSummary(PrintWriter out, ChangeSet cs) {
        if (cs.isClean()) {
            out.println("Sync limpio: nada que pushear ni pullear.");
            return;
        }
        out.println("Cambios pendientes:");
        out.println("  toUpload:       " + cs.toUpload().size());
        out.println("  toDownload:     " + cs.toDownload().size());
        out.println("  toDeleteLocal:  " + cs.toDeleteLocal().size());
        out.println("  toDeleteRemote: " + cs.toDeleteRemote().size());
        out.println("  conflicts:      " + cs.conflicts().size());
        out.println();
        printGroup(out, "Para subir (push):", cs.toUpload());
        printGroup(out, "Para bajar (pull):", cs.toDownload());
        printGroup(out, "Para borrar local (pull):", cs.toDeleteLocal());
        printGroup(out, "Para borrar remoto (push):", cs.toDeleteRemote());
        printGroup(out, "CONFLICTOS (requieren resolve):", cs.conflicts());
    }

    private static void printGroup(PrintWriter out, String header, java.util.Set<String> paths) {
        if (paths.isEmpty()) return;
        out.println(header);
        for (String p : paths) out.println("  " + p);
    }

    /** Resumen rápido desde el state.json del watcher (sin reescanear). */
    private static void printFromState(PrintWriter out, WatchState s) {
        out.println("(desde state.json del watcher — last poll " + s.lastRemoteCheckAt() + ")");
        WatchState.Summary sm = s.summary();
        int pending = sm.localChanged() + sm.remoteChanged() + sm.conflicts();
        if (pending == 0) {
            out.println("Sync limpio: nada que pushear ni pullear.");
            return;
        }
        out.println("Cambios pendientes:");
        out.println("  localChanged:  " + sm.localChanged());
        out.println("  remoteChanged: " + sm.remoteChanged());
        out.println("  conflicts:     " + sm.conflicts());
        if (!s.remoteReachable()) {
            out.println("(warning: remoto no alcanzable en el último intento)");
        }
    }
}
