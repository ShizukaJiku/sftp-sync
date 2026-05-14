package io.github.shizuka.sftpsync.sftp;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.util.Hostname;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Lock remoto basado en {@code SSH_FXP_OPEN} con flags {@code Create|Exclusive|Write}.
 */
public final class RemoteLockManager {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteLockManager.class);

    public static final String FILE_NAME = "lock";
    public static final String NEW_NAME = "lock.new";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private RemoteLockManager() {}

    public static String lockPath(String remoteRoot) {
        return RemoteManifestStore.joinRemote(
            RemoteManifestStore.syncDir(remoteRoot), FILE_NAME);
    }

    public static String lockNewPath(String remoteRoot) {
        return RemoteManifestStore.joinRemote(
            RemoteManifestStore.syncDir(remoteRoot), NEW_NAME);
    }

    public static boolean isOrphan(LockInfo lock) {
        return isOrphan(lock, Instant.now());
    }

    static boolean isOrphan(LockInfo lock, Instant now) {
        try {
            Instant lastHb = Instant.parse(lock.lastHeartbeatAt());
            return Duration.between(lastHb, now).getSeconds() > lock.ttlSeconds();
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    public static String makeHolder(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        long pid = ProcessHandle.current().pid();
        String shortId = clientId.length() >= 8 ? clientId.substring(0, 8) : clientId;
        return Hostname.get() + "+pid" + pid + "+" + shortId;
    }

    public static LockInfo acquire(SftpClient sftp, String remoteRoot,
                                   String holder, String operation,
                                   int ttlSeconds) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(operation, "operation");

        RemoteManifestStore.mkdirs(sftp, RemoteManifestStore.syncDir(remoteRoot));

        String path = lockPath(remoteRoot);
        LockInfo lock = LockInfo.now(holder, operation, ttlSeconds);
        byte[] bytes = (JSON_PRETTY.asString(lock) + "\n").getBytes(StandardCharsets.UTF_8);

        try {
            try (SftpClient.CloseableHandle h = sftp.open(path,
                    EnumSet.of(OpenMode.Create, OpenMode.Exclusive, OpenMode.Write))) {
                sftp.write(h, 0, bytes, 0, bytes.length);
            }
            return lock;
        } catch (SftpException e) {
            LockInfo existing = tryRead(sftp, remoteRoot);
            if (existing != null) {
                throw new LockHeldException(existing, e);
            }
            throw e;
        }
    }

    public static boolean release(SftpClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        String path = lockPath(remoteRoot);
        if (!RemoteManifestStore.statExists(sftp, path)) return false;
        sftp.remove(path);
        return true;
    }

    public static LockInfo read(SftpClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        String path = lockPath(remoteRoot);
        byte[] bytes;
        try (InputStream is = sftp.read(path)) {
            bytes = is.readAllBytes();
        } catch (SftpException e) {
            if (e.getStatus() == 2) return null;
            throw e;
        }
        return JSON.std.beanFrom(LockInfo.class, new String(bytes, StandardCharsets.UTF_8));
    }

    private static LockInfo tryRead(SftpClient sftp, String remoteRoot) {
        try { return read(sftp, remoteRoot); } catch (IOException ignored) { return null; }
    }

    static void writeOverwrite(SftpClient sftp, String remoteRoot, LockInfo newLock)
            throws IOException {
        String dir = RemoteManifestStore.syncDir(remoteRoot);
        RemoteManifestStore.mkdirs(sftp, dir);
        String target = lockPath(remoteRoot);
        String tmp = lockNewPath(remoteRoot);
        byte[] bytes = (JSON_PRETTY.asString(newLock) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            try (SftpClient.CloseableHandle h = sftp.open(tmp,
                    EnumSet.of(OpenMode.Create, OpenMode.Write, OpenMode.Truncate))) {
                sftp.write(h, 0, bytes, 0, bytes.length);
            }
            PosixRename.overwrite(sftp, tmp, target);
        } catch (IOException e) {
            try {
                if (RemoteManifestStore.statExists(sftp, tmp)) sftp.remove(tmp);
            } catch (IOException ignore) {}
            throw e;
        }
    }

    public static LockInfo acquireOrSteal(SftpClient sftp, String remoteRoot,
                                          String holder, String operation,
                                          int ttlSeconds) throws IOException {
        try {
            return acquire(sftp, remoteRoot, holder, operation, ttlSeconds);
        } catch (LockHeldException e) {
            if (!isOrphan(e.holder())) {
                throw e;
            }
            LOG.info("Detectado lock huérfano de {} (lastHeartbeatAt={}). Robándolo.",
                e.holder().holder(), e.holder().lastHeartbeatAt());
            return steal(sftp, remoteRoot, holder, operation, ttlSeconds, e.holder());
        }
    }

    private static LockInfo steal(SftpClient sftp, String remoteRoot,
                                  String newHolder, String operation,
                                  int ttlSeconds, LockInfo prevHolder) throws IOException {
        String tmp = lockNewPath(remoteRoot) + "." + safeForFilename(newHolder);
        String target = lockPath(remoteRoot);
        LockInfo candidate = LockInfo.now(newHolder, operation, ttlSeconds);
        byte[] bytes = (JSON_PRETTY.asString(candidate) + "\n").getBytes(StandardCharsets.UTF_8);

        try {
            if (RemoteManifestStore.statExists(sftp, tmp)) sftp.remove(tmp);
        } catch (IOException ignore) {}

        try (SftpClient.CloseableHandle h = sftp.open(tmp,
                EnumSet.of(OpenMode.Create, OpenMode.Exclusive, OpenMode.Write))) {
            sftp.write(h, 0, bytes, 0, bytes.length);
        }

        try {
            PosixRename.overwrite(sftp, tmp, target);
        } catch (IOException e) {
            try { sftp.remove(tmp); } catch (IOException ignore) {}
            throw e;
        }

        LockInfo afterSteal = read(sftp, remoteRoot);
        if (afterSteal == null || !candidate.holder().equals(afterSteal.holder())
            || !candidate.acquiredAt().equals(afterSteal.acquiredAt())) {
            throw new LockHeldException(
                afterSteal != null ? afterSteal : prevHolder, null);
        }
        return afterSteal;
    }

    private static String safeForFilename(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
