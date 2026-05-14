package io.github.shizuka.sftpsync.sftp;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.common.SftpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Upload / download primitivos sobre SFTP (MINA SSHD), sin lógica de manifest.
 */
public final class RemoteTransfer {

    public static final String STAGING_DIR_NAME = "staging";
    private static final int BUFFER_SIZE = 64 * 1024;

    private RemoteTransfer() {}

    public static String stagingDir(String remoteRoot) {
        return RemoteManifestStore.joinRemote(
            RemoteManifestStore.syncDir(remoteRoot), STAGING_DIR_NAME);
    }

    public static String stagingPath(String remoteRoot, String sha256) {
        return RemoteManifestStore.joinRemote(stagingDir(remoteRoot), sha256);
    }

    public static String uploadToStaging(SftpClient sftp, String remoteRoot,
                                         Path localFile, String sha256) throws IOException {
        RemoteManifestStore.mkdirs(sftp, stagingDir(remoteRoot));
        String staged = stagingPath(remoteRoot, sha256);
        long localSize = Files.size(localFile);

        try {
            Attributes existing = sftp.stat(staged);
            if (existing.getSize() == localSize) {
                return staged;
            }
        } catch (SftpException e) {
            if (e.getStatus() != 2) throw e;
        }

        try (InputStream is = Files.newInputStream(localFile);
             SftpClient.CloseableHandle h = sftp.open(staged,
                 EnumSet.of(OpenMode.Create, OpenMode.Write, OpenMode.Truncate))) {
            byte[] buf = new byte[BUFFER_SIZE];
            long offset = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                sftp.write(h, offset, buf, 0, n);
                offset += n;
            }
        }
        return staged;
    }

    public static void promoteFromStaging(SftpClient sftp, String stagedPath,
                                          String finalPath) throws IOException {
        ensureRemoteParentDirs(sftp, finalPath);
        PosixRename.overwrite(sftp, stagedPath, finalPath);
    }

    public static void download(SftpClient sftp, String remotePath, Path localTarget)
            throws IOException {
        Files.createDirectories(localTarget.getParent());
        try (InputStream is = sftp.read(remotePath);
             OutputStream os = Files.newOutputStream(localTarget)) {
            is.transferTo(os);
        }
    }

    public static boolean deleteRemote(SftpClient sftp, String remotePath) throws IOException {
        if (!RemoteManifestStore.statExists(sftp, remotePath)) return false;
        sftp.remove(remotePath);
        return true;
    }

    private static void ensureRemoteParentDirs(SftpClient sftp, String remotePath)
            throws IOException {
        int lastSlash = remotePath.lastIndexOf('/');
        if (lastSlash <= 0) return;
        RemoteManifestStore.mkdirs(sftp, remotePath.substring(0, lastSlash));
    }

    public static int gcStaging(SftpClient sftp, String remoteRoot,
                                Set<String> activeHashes) throws IOException {
        String dir = stagingDir(remoteRoot);
        if (!RemoteManifestStore.statExists(sftp, dir)) return 0;
        int removed = 0;
        for (DirEntry entry : sftp.readDir(dir)) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            if (entry.getAttributes().isDirectory()) continue;
            if (activeHashes.contains(name)) continue;
            sftp.remove(RemoteManifestStore.joinRemote(dir, name));
            removed++;
        }
        return removed;
    }
}
