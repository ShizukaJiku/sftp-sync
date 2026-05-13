package io.github.shizuka.sftpsync.sftp;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.RenameFlags;
import net.schmizz.sshj.sftp.SFTPClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Upload / download primitivos sobre SFTP, sin lógica de manifest.
 *
 * <p>Push usa "content-addressed staging": cada archivo se sube a
 * {@code <remoteRoot>/.sync/staging/<sha256>}. Una vez que todos llegan, se hace
 * {@code posix-rename} de staging al path final. Si el staging file ya existe con
 * el mismo hash, lo reusamos (resume implícito).
 *
 * <p>Pull descarga a un tmp local, lo verifica por hash si se pasa expected, y
 * luego hace {@code ATOMIC_MOVE} al path final.
 */
public final class RemoteTransfer {

    /** Subcarpeta de staging dentro de .sync/. */
    public static final String STAGING_DIR_NAME = "staging";

    /** Tamaño del buffer de I/O para transferencias en streaming. */
    private static final int BUFFER_SIZE = 64 * 1024;

    private RemoteTransfer() {}

    /** Path absoluto remoto al directorio de staging. */
    public static String stagingDir(String remoteRoot) {
        return RemoteManifestStore.joinRemote(
            RemoteManifestStore.syncDir(remoteRoot), STAGING_DIR_NAME);
    }

    /** Path absoluto remoto a un archivo en staging (content-addressed). */
    public static String stagingPath(String remoteRoot, String sha256) {
        return RemoteManifestStore.joinRemote(stagingDir(remoteRoot), sha256);
    }

    /**
     * Sube {@code localFile} al staging remoto con nombre {@code <sha256>}.
     * Si ya existe un archivo de staging con el mismo nombre, no lo re-sube
     * (resume implícito; asumimos que el hash es identidad).
     *
     * @return path remoto del archivo en staging.
     */
    public static String uploadToStaging(SFTPClient sftp, String remoteRoot,
                                         Path localFile, String sha256) throws IOException {
        sftp.mkdirs(stagingDir(remoteRoot));
        String staged = stagingPath(remoteRoot, sha256);

        FileAttributes existing = sftp.statExistence(staged);
        if (existing != null && existing.getSize() == Files.size(localFile)) {
            return staged; // resume: ya está
        }

        try (RemoteFile rf = sftp.open(staged,
                EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC));
             OutputStream os = rf.new RemoteFileOutputStream();
             InputStream is = Files.newInputStream(localFile)) {
            is.transferTo(os);
        }
        return staged;
    }

    /**
     * Mueve un archivo del staging a su path final con {@code posix-rename}
     * atómico (sobrescribe si el target existe).
     *
     * <p>Crea los directorios intermedios si no existen.
     */
    public static void promoteFromStaging(SFTPClient sftp, String stagedPath,
                                          String finalPath) throws IOException {
        ensureRemoteParentDirs(sftp, finalPath);
        sftp.rename(stagedPath, finalPath, EnumSet.of(RenameFlags.OVERWRITE));
    }

    /**
     * Descarga un archivo remoto a un path local. No verifica hash — eso lo hace
     * el caller después con {@link io.github.shizuka.sftpsync.util.Hashing}.
     */
    public static void download(SFTPClient sftp, String remotePath, Path localTarget)
            throws IOException {
        Files.createDirectories(localTarget.getParent());
        try (RemoteFile rf = sftp.open(remotePath, EnumSet.of(OpenMode.READ));
             InputStream is = rf.new RemoteFileInputStream();
             OutputStream os = Files.newOutputStream(localTarget)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    /**
     * Borra un archivo remoto. Idempotente: si no existe, no hace nada.
     * No borra el directorio padre aunque quede vacío.
     */
    public static boolean deleteRemote(SFTPClient sftp, String remotePath) throws IOException {
        if (sftp.statExistence(remotePath) == null) return false;
        sftp.rm(remotePath);
        return true;
    }

    /**
     * Crea los directorios padres de {@code remotePath} si no existen. Idempotente.
     */
    private static void ensureRemoteParentDirs(SFTPClient sftp, String remotePath)
            throws IOException {
        int lastSlash = remotePath.lastIndexOf('/');
        if (lastSlash <= 0) return; // root
        String parent = remotePath.substring(0, lastSlash);
        sftp.mkdirs(parent);
    }

    /**
     * Borra del staging remoto todos los archivos cuyo nombre (= sha256) NO esté
     * en {@code activeHashes}. Útil para limpiar archivos huérfanos dejados por
     * pushes que crashearon antes del rename a manifest.
     *
     * @return cantidad de archivos borrados.
     */
    public static int gcStaging(SFTPClient sftp, String remoteRoot,
                                Set<String> activeHashes) throws IOException {
        String dir = stagingDir(remoteRoot);
        if (sftp.statExistence(dir) == null) return 0;
        int removed = 0;
        List<RemoteResourceInfo> entries = sftp.ls(dir);
        for (RemoteResourceInfo info : entries) {
            String name = info.getName();
            if (info.isDirectory()) continue;
            if (activeHashes.contains(name)) continue;
            sftp.rm(info.getPath());
            removed++;
        }
        return removed;
    }
}
