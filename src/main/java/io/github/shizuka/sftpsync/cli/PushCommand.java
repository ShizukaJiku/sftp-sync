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
import io.github.shizuka.sftpsync.sftp.LockHeartbeat;
import io.github.shizuka.sftpsync.sftp.LockHeldException;
import io.github.shizuka.sftpsync.sftp.LockInfo;
import io.github.shizuka.sftpsync.sftp.RemoteLockManager;
import io.github.shizuka.sftpsync.sftp.RemoteManifestStore;
import io.github.shizuka.sftpsync.sftp.RemoteTransfer;
import io.github.shizuka.sftpsync.sftp.SftpSession;
import io.github.shizuka.sftpsync.sftp.SftpSession.HostKeyMode;
import io.github.shizuka.sftpsync.util.Hashing;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * Sube cambios locales al remoto, aplicando el ChangeSet del three-way diff.
 *
 * <p>Flujo (docs/design.md §6.3):
 * <ol>
 *   <li>Scan local + bajar manifest remoto + cargar base.</li>
 *   <li>Diff. Si hay conflictos → abortar (no se toma lock).</li>
 *   <li>Adquirir lock remoto. Si está tomado → abortar limpio.</li>
 *   <li>Re-bajar manifest remoto bajo lock (puede haber cambiado entre el primer fetch
 *       y el acquire). Si hay nuevos conflictos → release lock y abortar.</li>
 *   <li>Upload de archivos a {@code .sync/staging/<sha256>} (content-addressed).</li>
 *   <li>Promote: {@code posix-rename} de staging al path final.</li>
 *   <li>Aplicar deletes remotos.</li>
 *   <li>Subir manifest nuevo atómico.</li>
 *   <li>Release lock.</li>
 *   <li>Actualizar {@code .sync/base.json} local con el manifest nuevo.</li>
 * </ol>
 *
 * <p>Garantía: si crashea en cualquier punto antes del rename atómico del manifest,
 * el remoto sigue viendo el manifest viejo. Los archivos en staging son invisibles.
 * Un próximo push los reusa (resume implícito por hash).
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — push exitoso (o nada que pushear).</li>
 *   <li>{@code 1} — sin config.</li>
 *   <li>{@code 2/3/5} — auth/SSH/I/O.</li>
 *   <li>{@code 6} — lock ya tomado por otro cliente.</li>
 *   <li>{@code 7} — conflictos pendientes — corré {@code status} para ver, resolvé y reintentá.</li>
 * </ul>
 */
@Command(
    name = "push",
    description = "Subir cambios locales al remoto. Aborta si hay conflictos."
)
public final class PushCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure", description = "Aceptar cualquier host key.")
    boolean insecure;

    @Option(names = "--no-cache", description = "Forzar rehash local.")
    boolean noCache;

    @Option(names = "--gc",
            description = "Borrar archivos huérfanos en <remoteRoot>/.sync/staging/ "
                + "(content-addressed files no referenciados por el manifest).")
    boolean gc;

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
            // --- Bajar manifest remoto + base, hacer diff inicial ---
            Manifest remoteManifest = RemoteManifestStore.loadOrEmpty(session.sftp(), remoteRoot, config.clientId());
            Manifest baseManifest = BaseStore.loadOrEmpty(root);
            ChangeSet cs = ThreeWayDiffer.diff(baseManifest, localManifest, remoteManifest);

            if (cs.hasConflicts()) {
                err.println("Conflictos detectados — push abortado.");
                err.println("Corré 'sftp-sync status' para verlos, resolvé y reintentá.");
                return 7;
            }
            if (cs.toUpload().isEmpty() && cs.toDeleteRemote().isEmpty()) {
                out.println("Nada que pushear.");
                return 0;
            }

            // --- Adquirir lock (con steal de huérfano si aplica) ---
            String holder = RemoteLockManager.makeHolder(config.clientId());
            LockInfo lock;
            try {
                lock = RemoteLockManager.acquireOrSteal(session.sftp(), remoteRoot,
                    holder, "push", LockInfo.DEFAULT_TTL_SECONDS);
            } catch (LockHeldException e) {
                err.println("Lock ya tomado por " + e.holder().holder()
                    + " (" + e.holder().operation() + " desde " + e.holder().acquiredAt() + ").");
                err.println("Esperá a que termine o liberalo con 'sftp-sync lock --release'.");
                return 6;
            }

            // --- Heartbeat sobre una sesión SSH dedicada, para que sus OPEN/RENAME no
            // se interlearven con los WRITE largos del upload sobre la sesión principal. ---
            Manifest newRemoteManifest;
            try (SftpSession hbSession = SftpSession.open(config.remote(), mode);
                 LockHeartbeat hb = LockHeartbeat.start(hbSession.sftp(), remoteRoot, lock)) {

                // --- Re-bajar manifest bajo lock (puede haber cambiado) ---
                Manifest remoteUnderLock = RemoteManifestStore.loadOrEmpty(
                    session.sftp(), remoteRoot, config.clientId());
                ChangeSet csFinal = ThreeWayDiffer.diff(
                    baseManifest, localManifest, remoteUnderLock);
                if (csFinal.hasConflicts()) {
                    // hb se cierra al salir del try; release ocurre abajo en el catch del outer.
                    tryRelease(session, remoteRoot, err);
                    err.println("Conflictos aparecieron entre el primer fetch y el lock acquire.");
                    err.println("Otro cliente pusheó. Corré 'sftp-sync status'.");
                    return 7;
                }

                // --- Upload + promote ---
                Map<String, String> promotions = new LinkedHashMap<>(); // staged → finalRemote
                for (String relPath : csFinal.toUpload()) {
                    ManifestEntry entry = localManifest.entries().get(relPath);
                    Path localFile = root.resolve(relPath);

                    // Mitigación 3.6: re-hash justo antes del upload. Si el archivo
                    // cambió entre el scan y el push, abortarlo individualmente —
                    // se reintentará en el próximo push con el contenido nuevo.
                    String actualHash = Hashing.sha256(localFile);
                    if (!actualHash.equals(entry.sha256())) {
                        err.println("skip    " + relPath
                            + " (cambió durante el push, se reintentará)");
                        continue;
                    }

                    String staged = RemoteTransfer.uploadToStaging(
                        session.sftp(), remoteRoot, localFile, entry.sha256());
                    String finalRemote = RemoteManifestStore.joinRemote(remoteRoot, relPath);
                    promotions.put(staged, finalRemote);
                    out.println("upload  " + relPath);
                }
                for (Map.Entry<String, String> e : promotions.entrySet()) {
                    RemoteTransfer.promoteFromStaging(session.sftp(), e.getKey(), e.getValue());
                }

                // --- Deletes remotos ---
                for (String relPath : csFinal.toDeleteRemote()) {
                    String full = RemoteManifestStore.joinRemote(remoteRoot, relPath);
                    RemoteTransfer.deleteRemote(session.sftp(), full);
                    out.println("delete  " + relPath);
                }

                if (gc) {
                    Set<String> activeHashes = new HashSet<>();
                    for (ManifestEntry e : remoteUnderLock.entries().values()) {
                        activeHashes.add(e.sha256());
                    }
                    for (String relPath : csFinal.toUpload()) {
                        activeHashes.add(localManifest.entries().get(relPath).sha256());
                    }
                    int removed = RemoteTransfer.gcStaging(session.sftp(), remoteRoot, activeHashes);
                    out.println("gc      " + removed + " archivos huérfanos borrados del staging");
                }

                // --- Manifest nuevo: arrancamos del remoto bajo lock y le aplicamos el delta.
                // Preservamos archivos remotos que el cliente no tenía (toDownload) para no
                // perderlos al sobrescribir con el local. ---
                Map<String, ManifestEntry> finalEntries = new TreeMap<>(remoteUnderLock.entries());
                for (String relPath : csFinal.toUpload()) {
                    finalEntries.put(relPath, localManifest.entries().get(relPath));
                }
                for (String relPath : csFinal.toDeleteRemote()) {
                    finalEntries.remove(relPath);
                }
                newRemoteManifest = Manifest.of(config.clientId(), finalEntries);
                RemoteManifestStore.save(session.sftp(), remoteRoot, newRemoteManifest);
                // El upload del manifest es el commit-point. A partir de acá podemos
                // detener el heartbeat y soltar el lock sin riesgo de que otro cliente
                // vea un estado inconsistente.
            } // hb + hbSession cerrados acá, antes del release.

            // --- Release lock (después de cerrar el heartbeat para evitar carrera). ---
            RemoteLockManager.release(session.sftp(), remoteRoot);

            // --- Update base local con el manifest remoto post-push (ancla del próximo diff). ---
            BaseStore.save(root, newRemoteManifest);

            out.println();
            out.println("Push OK.");
            return 0;

        } catch (UserAuthException e) {
            err.println("Autenticación falló: " + e.getMessage());
            return 2;
        } catch (TransportException e) {
            err.println("Error SSH: " + e.getMessage());
            return 3;
        } catch (IOException e) {
            err.println("Error durante push: " + e.getMessage());
            return 5;
        }
    }

    private static void tryRelease(SftpSession session, String remoteRoot, PrintWriter err) {
        try {
            RemoteLockManager.release(session.sftp(), remoteRoot);
        } catch (IOException ignore) {
            err.println("Warning: no pude liberar el lock tras un error.");
        }
    }
}
