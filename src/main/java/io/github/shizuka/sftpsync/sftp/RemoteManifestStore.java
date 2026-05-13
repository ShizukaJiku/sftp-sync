package io.github.shizuka.sftpsync.sftp;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RenameFlags;
import net.schmizz.sshj.sftp.SFTPClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Lectura y escritura del manifest remoto en {@code <remoteRoot>/.sync/manifest.json}
 * vía SFTP.
 *
 * <p>Stateless. Todos los métodos son estáticos y reciben un {@link SFTPClient}
 * activo (la sesión SSH es responsabilidad del caller — típicamente
 * {@link SftpSession}).
 *
 * <p><b>Atomicidad de escritura:</b> {@link #save} escribe primero a
 * {@code manifest.json.tmp} y luego hace {@code rename} sobre el target. sshj 0.40
 * usa la extensión {@code posix-rename@openssh.com} cuando el servidor la anuncia
 * (OpenSSH Linux la trae), lo que hace el rename atómico. Si crasheamos a mitad de
 * escritura, el remoto sigue viendo el manifest viejo intacto.
 *
 * <p><b>Paths remotos:</b> siempre con forward slashes. SFTP es POSIX-style en el
 * wire, sin importar el SO del cliente.
 */
public final class RemoteManifestStore {

    /** Nombre de la carpeta de estado remota dentro de {@code remoteRoot}. */
    public static final String DIR_NAME = SyncConfigStore.DIR_NAME;

    /** Nombre del archivo de manifest remoto. */
    public static final String FILE_NAME = "manifest.json";

    /** Nombre del archivo temporal usado para escritura atómica. */
    public static final String TMP_NAME = FILE_NAME + ".tmp";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private RemoteManifestStore() {}

    /** Path absoluto remoto a {@code <remoteRoot>/.sync}. */
    public static String syncDir(String remoteRoot) {
        return joinRemote(remoteRoot, DIR_NAME);
    }

    /** Path absoluto remoto a {@code <remoteRoot>/.sync/manifest.json}. */
    public static String manifestPath(String remoteRoot) {
        return joinRemote(syncDir(remoteRoot), FILE_NAME);
    }

    /** Path absoluto remoto al archivo temporal usado para el rename atómico. */
    public static String tmpPath(String remoteRoot) {
        return joinRemote(syncDir(remoteRoot), TMP_NAME);
    }

    /**
     * {@code true} si {@code <remoteRoot>/.sync/manifest.json} existe en el remoto.
     *
     * @throws IOException si falla la operación SFTP (no por "no existe" — eso es {@code false}).
     */
    public static boolean exists(SFTPClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        FileAttributes attrs = sftp.statExistence(manifestPath(remoteRoot));
        return attrs != null;
    }

    /**
     * Lee el manifest remoto y lo deserializa.
     *
     * @throws NoSuchFileException si {@code .sync/manifest.json} no existe en el remoto.
     * @throws IOException         si hay error de SFTP o JSON corrupto.
     */
    public static Manifest load(SFTPClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        return readManifest(sftp, manifestPath(remoteRoot));
    }

    /**
     * Lee el manifest remoto. Si no existe, retorna {@link Manifest#empty} con
     * el {@code clientId} dado — útil para callers que tratan "remoto vacío" como
     * un estado normal. Hace UNA sola round-trip SFTP (open + catch
     * NoSuchFileException) en lugar de exists+open separados.
     */
    public static Manifest loadOrEmpty(SFTPClient sftp, String remoteRoot, String clientId)
            throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        try {
            return readManifest(sftp, manifestPath(remoteRoot));
        } catch (NoSuchFileException e) {
            return Manifest.empty(clientId);
        }
    }

    private static Manifest readManifest(SFTPClient sftp, String path) throws IOException {
        byte[] bytes;
        try (RemoteFile rf = sftp.open(path, EnumSet.of(OpenMode.READ));
             InputStream is = rf.new RemoteFileInputStream()) {
            bytes = is.readAllBytes();
        } catch (net.schmizz.sshj.sftp.SFTPException e) {
            if (e.getStatusCode() == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE) {
                throw new NoSuchFileException(path);
            }
            throw e;
        }
        return JSON.std.beanFrom(Manifest.class, new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Escribe el manifest al remoto de forma atómica.
     *
     * <p>Crea {@code <remoteRoot>/.sync/} si no existe. Escribe a
     * {@code manifest.json.tmp} y luego hace {@code rename} sobre {@code manifest.json}.
     * En servidores OpenSSH Linux esto es atómico (posix-rename). Si crasheamos a
     * mitad, el manifest viejo sigue intacto.
     *
     * <p>En caso de fallo intenta borrar el {@code .tmp} colgado (best effort).
     */
    public static void save(SFTPClient sftp, String remoteRoot, Manifest manifest)
            throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        Objects.requireNonNull(manifest, "manifest");

        String dir = syncDir(remoteRoot);
        String tmp = tmpPath(remoteRoot);
        String target = manifestPath(remoteRoot);

        sftp.mkdirs(dir);

        String json = JSON_PRETTY.asString(manifest) + "\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        try {
            try (RemoteFile rf = sftp.open(tmp,
                    EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC));
                 OutputStream os = rf.new RemoteFileOutputStream()) {
                os.write(bytes);
            }
            // Overwrite requiere flag explícita. Con OVERWRITE, sshj usa la extensión
            // posix-rename@openssh.com si el server la anuncia (OpenSSH la trae),
            // garantizando un rename atómico sobre target existente. Sin la flag,
            // SSH_FXP_RENAME estándar falla con SSH_FX_FAILURE si target existe.
            sftp.rename(tmp, target, EnumSet.of(RenameFlags.OVERWRITE));
        } catch (IOException e) {
            // Cleanup best-effort del tmp colgado.
            try {
                if (sftp.statExistence(tmp) != null) {
                    sftp.rm(tmp);
                }
            } catch (IOException ignore) { /* nada */ }
            throw e;
        }
    }

    /**
     * Concatena dos componentes de un path remoto, normalizando barras.
     * Garantiza una sola {@code /} entre componentes, sin importar si {@code base}
     * termina con barra o {@code sub} empieza con barra.
     */
    public static String joinRemote(String base, String sub) {
        if (base.isEmpty()) return sub;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String s = sub.startsWith("/")  ? sub.substring(1)                    : sub;
        return b + "/" + s;
    }
}
