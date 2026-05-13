package io.github.shizuka.sftpsync.sftp;

import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.util.PathExpansion;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Sesión de conexión a un servidor SFTP, encapsulando el ciclo de vida del
 * {@link SSHClient} + {@link SFTPClient} de sshj.
 *
 * <p>Diseñado para uso con try-with-resources:
 * <pre>{@code
 * try (SftpSession s = SftpSession.open(remote, HostKeyMode.STRICT)) {
 *     s.sftp().ls(remoteRoot).forEach(System.out::println);
 * }
 * }</pre>
 *
 * <p><b>Verificación de host key:</b>
 * <ul>
 *   <li>{@code STRICT} (default): consulta {@code ~/.ssh/known_hosts}. Si el
 *       host no está, la conexión falla con un mensaje útil. Es la forma segura
 *       y recomendada.</li>
 *   <li>{@code INSECURE}: acepta cualquier host key sin chequear. Solo para
 *       debug / primer setup. NUNCA usar en producción.</li>
 * </ul>
 *
 * <p><b>Authentication:</b> ramifica según {@link RemoteConfig}:
 * <ul>
 *   <li>Si {@code hasKey()} — usa clave pública (sin passphrase soportada en MVP).</li>
 *   <li>Si {@code hasPassword()} — usa password.</li>
 *   <li>Si ambas — gana key auth.</li>
 *   <li>Si ninguna — falla con {@link IOException}.</li>
 * </ul>
 */
public final class SftpSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SftpSession.class);

    /** Timeout TCP del connect inicial. */
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    /** Timeout para operaciones SSH/SFTP individuales. */
    private static final int OPERATION_TIMEOUT_MS = 30_000;

    static {
        // sshj 0.40 puede correr sin BouncyCastle usando los providers JDK
        // (SunJCE + SunEC). Eso da AES-256-CTR/GCM, ssh-rsa, ssh-ed25519 y
        // ECDH — más que suficiente para emberstack/OpenSSH (que negocia
        // AES-256-CTR sin problema). Sin BC, sshj loguea ~17 warnings al
        // arrancar ("Cipher [X] disabled") que son ruido pero no rompen nada.
        //
        // Mantener BC desactivado simplifica el build native-image: no hay
        // que verificar el provider en build time ni declarar 100 clases en
        // --initialize-at-build-time. Ver issue hierynomus/sshj#782 para el
        // contexto histórico.
        SecurityUtils.setRegisterBouncyCastle(false);
    }

    public enum HostKeyMode {
        /** Consulta ~/.ssh/known_hosts; falla si el host no está cacheado. */
        STRICT,
        /** Acepta cualquier host key. Solo para debug/setup. */
        INSECURE
    }

    private final SSHClient ssh;
    private final SFTPClient sftp;

    private SftpSession(SSHClient ssh, SFTPClient sftp) {
        this.ssh = ssh;
        this.sftp = sftp;
    }

    /**
     * Abre una nueva sesión SFTP contra el remoto descrito por {@code config}.
     *
     * @param config datos de conexión (host, port, user, keyPath OR password).
     * @param mode   política de verificación de host key.
     * @throws IOException si falla cualquier paso (DNS, connect, host key,
     *                     auth, key file no existe, sin auth method configurado).
     */
    public static SftpSession open(RemoteConfig config, HostKeyMode mode) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(mode, "mode");

        if (!config.hasKey() && !config.hasPassword()) {
            throw new IOException(
                "Config no tiene método de autenticación. Setear keyPath o password.");
        }

        String resolvedKeyPath = null;
        if (config.hasKey()) {
            resolvedKeyPath = PathExpansion.expandTilde(config.keyPath());
            Path keyFile = Path.of(resolvedKeyPath);
            if (!Files.exists(keyFile)) {
                throw new IOException("Clave SSH no encontrada: " + keyFile);
            }
            if (!Files.isReadable(keyFile)) {
                throw new IOException("Clave SSH no legible: " + keyFile);
            }
        }

        SSHClient ssh = new SSHClient();
        ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ssh.setTimeout(OPERATION_TIMEOUT_MS);

        try {
            configureHostKeyVerification(ssh, mode, config.host());
            ssh.connect(config.host(), config.port());

            if (resolvedKeyPath != null) {
                ssh.authPublickey(config.user(), resolvedKeyPath);
            } else {
                ssh.authPassword(config.user(), config.password());
            }

            SFTPClient sftp = ssh.newSFTPClient();
            return new SftpSession(ssh, sftp);
        } catch (IOException e) {
            // Cualquier fallo en setup: cerramos lo que hayamos abierto.
            try { ssh.close(); } catch (IOException ignore) { /* nada */ }
            throw e;
        }
    }

    private static void configureHostKeyVerification(
            SSHClient ssh, HostKeyMode mode, String host) throws IOException {
        if (mode == HostKeyMode.INSECURE) {
            LOG.warn("Host key verification DISABLED (--insecure). MITM possible.");
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            return;
        }
        try {
            ssh.loadKnownHosts();
        } catch (IOException e) {
            throw new IOException(
                "No ~/.ssh/known_hosts encontrado. Opciones:\n"
                + "  1. SSH una vez al server: ssh " + host + "\n"
                + "  2. Usar --insecure (NO recomendado salvo primer setup)",
                e);
        }
    }

    /** SFTP client con todas las operaciones de filesystem remoto. */
    public SFTPClient sftp() {
        return sftp;
    }

    /** Versión del server SSH (banner string), útil para diagnóstico. */
    public String serverVersion() {
        return ssh.getTransport().getServerVersion();
    }

    @Override
    public void close() throws IOException {
        try {
            sftp.close();
        } finally {
            ssh.close();
        }
    }
}
