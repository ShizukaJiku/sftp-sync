package io.github.shizuka.sftpsync.sftp;

import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.nativesupport.BouncyCastleInitializer;
import io.github.shizuka.sftpsync.util.PathExpansion;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Sesión SFTP basada en Apache MINA SSHD (sshd-core + sshd-sftp).
 *
 * <p>Reemplaza la implementación con sshj que tenía issue #871 con BouncyCastle
 * en native-image. MINA permite BC en native vía el patrón
 * {@code --initialize-at-build-time=org.bouncycastle} + {@code rerun} para las
 * clases con SecureRandom/DRBG. Ver pom.xml perfil {@code native}.
 */
public final class SftpSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SftpSession.class);

    private static final long CONNECT_TIMEOUT_SECONDS = 15;
    private static final long OPERATION_TIMEOUT_SECONDS = 30;

    static {
        // Asegurar que BC se registre. En native-image este clinit corre en
        // build-time (snapshot del provider en image heap); en JVM normal corre
        // al cargarse esta clase.
        BouncyCastleInitializer.ensureInitialized();
    }

    public enum HostKeyMode {
        STRICT,
        INSECURE
    }

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;

    private SftpSession(SshClient client, ClientSession session, SftpClient sftp) {
        this.client = client;
        this.session = session;
        this.sftp = sftp;
    }

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
            Path keyFile = Paths.get(resolvedKeyPath);
            if (!Files.exists(keyFile)) {
                throw new IOException("Clave SSH no encontrada: " + keyFile);
            }
            if (!Files.isReadable(keyFile)) {
                throw new IOException("Clave SSH no legible: " + keyFile);
            }
        }

        SshClient client = SshClient.setUpDefaultClient();
        configureHostKeyVerification(client, mode, config.host());
        if (resolvedKeyPath != null) {
            client.setKeyIdentityProvider(new FileKeyPairProvider(Paths.get(resolvedKeyPath)));
        }
        client.start();

        ClientSession session = null;
        SftpClient sftp = null;
        try {
            session = client.connect(config.user(), config.host(), config.port())
                .verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getSession();
            if (config.hasPassword()) {
                session.addPasswordIdentity(config.password());
            }
            session.auth().verify(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sftp = SftpClientFactory.instance().createSftpClient(session);
            return new SftpSession(client, session, sftp);
        } catch (IOException e) {
            closeQuietly(sftp);
            closeQuietly(session);
            closeQuietly(client);
            throw e;
        }
    }

    private static void configureHostKeyVerification(
            SshClient client, HostKeyMode mode, String host) throws IOException {
        if (mode == HostKeyMode.INSECURE) {
            LOG.warn("Host key verification DISABLED (--insecure). MITM possible.");
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            return;
        }
        Path knownHosts = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts");
        if (!Files.exists(knownHosts)) {
            throw new IOException(
                "No ~/.ssh/known_hosts encontrado. Opciones:\n"
                + "  1. SSH una vez al server: ssh " + host + "\n"
                + "  2. Usar --insecure (NO recomendado salvo primer setup)");
        }
        client.setServerKeyVerifier(new KnownHostsServerKeyVerifier(
            (s, addr, key) -> false,
            knownHosts));
    }

    public SftpClient sftp() {
        return sftp;
    }

    public String serverVersion() {
        return session.getServerVersion();
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        try { if (sftp != null) sftp.close(); } catch (IOException e) { first = e; }
        try { if (session != null) session.close(); } catch (IOException e) { if (first == null) first = e; }
        try { if (client != null) client.stop(); }
        catch (Exception e) { if (first == null) first = new IOException(e); }
        if (first != null) throw first;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception _) {}
    }
}
