package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
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
import io.github.shizuka.sftpsync.util.Hashing;
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
import java.util.UUID;
import java.util.concurrent.Callable;

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
 *   <li>Actualizar {@code base.json} solo si no quedaron conflictos pendientes.</li>
 * </ol>
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

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure", description = "Aceptar cualquier host key.")
    boolean insecure;

    @Option(names = "--no-cache", description = "Forzar rehash local.")
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

        try (SftpSession session = SftpSession.open(config.remote(), mode)) {
            Manifest remoteManifest = RemoteManifestStore.loadOrEmpty(
                session.sftp(), remoteRoot, config.clientId());
            if (remoteManifest.entries().isEmpty()
                && !RemoteManifestStore.exists(session.sftp(), remoteRoot)) {
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

            // --- Conflictos: bajar la versión remota como <path>.remote ---
            for (String relPath : cs.conflicts()) {
                ManifestEntry remoteEntry = remoteManifest.entries().get(relPath);
                if (remoteEntry == null) {
                    // Conflicto por borrado remoto vs cambio local: no hay nada que bajar.
                    err.println("conflict  " + relPath + " (remoto borrado, local editado)");
                    continue;
                }
                Path target = root.resolve(relPath + ".remote");
                Path tmp = stagedTmp(root, remoteEntry.sha256());
                downloadAndMove(session, remoteRoot, relPath, remoteEntry, tmp, target);
                err.println("conflict  " + relPath + " → " + relPath + ".remote");
            }

            // --- Downloads no-conflictivos ---
            for (String relPath : cs.toDownload()) {
                ManifestEntry remoteEntry = remoteManifest.entries().get(relPath);
                Path target = root.resolve(relPath);
                Path tmp = stagedTmp(root, remoteEntry.sha256());
                downloadAndMove(session, remoteRoot, relPath, remoteEntry, tmp, target);
                out.println("download  " + relPath);
            }

            // --- Deletes locales ---
            for (String relPath : cs.toDeleteLocal()) {
                Path target = root.resolve(relPath);
                Files.deleteIfExists(target);
                out.println("delete    " + relPath);
            }

            // --- Update base.json solo si no quedaron conflictos pendientes ---
            if (cs.hasConflicts()) {
                err.println();
                err.println("Pull con conflictos: base.json NO actualizado.");
                err.println("Resolvé los conflictos (mirar archivos *.remote) y reintentá.");
                return 7;
            }
            BaseStore.save(root, remoteManifest);

            out.println();
            out.println("Pull OK: "
                + cs.toDownload().size() + " bajados, "
                + cs.toDeleteLocal().size() + " borrados.");
            return 0;
        } catch (IOException e) {
            return SftpErrors.mapToExitCode(e, err);
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
     * Descarga {@code <remoteRoot>/<relPath>} a {@code tmp}, verifica que el hash
     * coincida con {@code expected.sha256()}, y mueve a {@code finalTarget} con
     * {@code ATOMIC_MOVE}. Si el hash no coincide, borra el tmp y tira IOException.
     */
    private static void downloadAndMove(SftpSession session, String remoteRoot,
                                        String relPath, ManifestEntry expected,
                                        Path tmp, Path finalTarget) throws IOException {
        String remotePath = RemoteManifestStore.joinRemote(remoteRoot, relPath);
        try {
            RemoteTransfer.download(session.sftp(), remotePath, tmp);
            String actualHash = Hashing.sha256(tmp);
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
}
