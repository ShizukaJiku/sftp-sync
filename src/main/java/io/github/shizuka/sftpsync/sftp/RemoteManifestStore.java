package io.github.shizuka.sftpsync.sftp;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.OpenMode;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Lectura y escritura del manifest remoto vía SFTP. Stateless.
 */
public final class RemoteManifestStore {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteManifestStore.class);

    public static final String DIR_NAME = SyncConfigStore.DIR_NAME;
    public static final String FILE_NAME = "manifest.json";
    public static final String TMP_NAME = FILE_NAME + ".tmp";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private RemoteManifestStore() {}

    public static String syncDir(String remoteRoot) {
        return joinRemote(remoteRoot, DIR_NAME);
    }

    public static String manifestPath(String remoteRoot) {
        return joinRemote(syncDir(remoteRoot), FILE_NAME);
    }

    public static String tmpPath(String remoteRoot) {
        return joinRemote(syncDir(remoteRoot), TMP_NAME);
    }

    public static boolean exists(SftpClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        return statExists(sftp, manifestPath(remoteRoot));
    }

    public static Manifest load(SftpClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        return readManifest(sftp, manifestPath(remoteRoot));
    }

    public static Manifest loadOrEmpty(SftpClient sftp, String remoteRoot, String clientId)
            throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        try {
            return readManifest(sftp, manifestPath(remoteRoot));
        } catch (NoSuchFileException e) {
            return Manifest.empty(clientId);
        }
    }

    private static Manifest readManifest(SftpClient sftp, String path) throws IOException {
        byte[] bytes;
        try (InputStream is = sftp.read(path)) {
            bytes = is.readAllBytes();
        } catch (SftpException e) {
            if (e.getStatus() == 2) {
                throw new NoSuchFileException(path);
            }
            throw e;
        }
        return JSON.std.beanFrom(Manifest.class, new String(bytes, StandardCharsets.UTF_8));
    }

    public static void save(SftpClient sftp, String remoteRoot, Manifest manifest)
            throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        Objects.requireNonNull(manifest, "manifest");

        String dir = syncDir(remoteRoot);
        String tmp = tmpPath(remoteRoot);
        String target = manifestPath(remoteRoot);

        mkdirs(sftp, dir);

        String json = JSON_PRETTY.asString(manifest) + "\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        try {
            try (SftpClient.CloseableHandle h = sftp.open(tmp,
                    EnumSet.of(OpenMode.Create, OpenMode.Write, OpenMode.Truncate))) {
                sftp.write(h, 0, bytes, 0, bytes.length);
            }
            PosixRename.overwrite(sftp, tmp, target);
        } catch (IOException e) {
            try {
                if (statExists(sftp, tmp)) sftp.remove(tmp);
            } catch (IOException ex) {
                LOG.debug("No pude limpiar tmp '{}' tras error: {}", tmp, ex.getMessage());
            }
            throw e;
        }
    }

    public static String joinRemote(String base, String sub) {
        if (base.isEmpty()) return sub;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String s = sub.startsWith("/") ? sub.substring(1) : sub;
        return b + "/" + s;
    }

    /**
     * {@code true} si el path remoto existe (cualquier tipo: archivo, dir, link).
     * Distingue "no existe" (status 2) de otros errores (que se propagan).
     */
    public static boolean statExists(SftpClient sftp, String path) throws IOException {
        try {
            sftp.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.getStatus() == 2) return false;
            throw e;
        }
    }

    /**
     * Crea {@code path} y todos sus directorios padre en el remoto que no
     * existan, recursivamente. Idempotente: si {@code path} ya existe retorna
     * sin tocarlo. Es la primitiva usada por {@code uploadToStaging} y por el
     * bootstrap de {@code init --bootstrap-remote}.
     *
     * <p>Si algún segmento padre no se puede crear por permisos, propaga la
     * {@link SftpException} sin tocar lo que ya existe.
     */
    public static void mkdirs(SftpClient sftp, String path) throws IOException {
        if (path.isEmpty() || "/".equals(path)) return;
        if (statExists(sftp, path)) return;
        int slash = path.lastIndexOf('/');
        if (slash > 0) mkdirs(sftp, path.substring(0, slash));
        try {
            sftp.mkdir(path);
        } catch (SftpException e) {
            if (e.getStatus() != 11 && !statExists(sftp, path)) throw e;
        }
    }
}
