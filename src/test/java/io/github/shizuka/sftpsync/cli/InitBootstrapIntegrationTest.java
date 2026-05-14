package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests del flag {@code --bootstrap-remote} de {@code init}.
 * Server SFTP in-process (MINA SshServer) con filesystem virtual sobre un
 * temp directory.
 *
 * <p>Garantiza que:
 * <ul>
 *   <li>Sin {@code --bootstrap-remote} → init es lazy, NO toca el server.</li>
 *   <li>Con {@code --bootstrap-remote} → init conecta y crea {@code remoteRoot}.</li>
 *   <li>Si el {@code remoteRoot} ya existe → idempotente, no falla.</li>
 *   <li>Si las credenciales son inválidas → exit code 2 (auth).</li>
 * </ul>
 */
class InitBootstrapIntegrationTest {

    private static final String USER = "user";
    private static final String PASS = "pass";

    private SshServer server;
    private Path serverRoot;
    private int port;

    @BeforeEach
    void startServer(@TempDir Path tmp) throws IOException {
        serverRoot = tmp.resolve("server-root");
        Files.createDirectories(serverRoot);

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(
            new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        server.setPasswordAuthenticator(
            (username, password, sess) -> USER.equals(username) && PASS.equals(password));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) server.stop(true);
    }

    @Test
    @DisplayName("init without --bootstrap-remote is lazy: no remote dir created")
    void init_noBootstrap_lazyDefault(@TempDir Path clientRoot) {
        int exit = runInit(clientRoot, "/proj", false);
        assertThat(exit).isZero();
        // El server NO debería tener /proj
        assertThat(serverRoot.resolve("proj")).doesNotExist();
    }

    @Test
    @DisplayName("init with --bootstrap-remote creates the remote root on the server")
    void init_bootstrapRemote_createsRemoteRoot(@TempDir Path clientRoot) {
        int exit = runInit(clientRoot, "/proj", true);
        assertThat(exit).isZero();
        assertThat(serverRoot.resolve("proj")).isDirectory();
    }

    @Test
    @DisplayName("init with --bootstrap-remote is idempotent if remote root already exists")
    void init_bootstrapRemote_alreadyExists_doesNotFail(@TempDir Path clientRoot) throws IOException {
        Files.createDirectories(serverRoot.resolve("proj"));
        int exit = runInit(clientRoot, "/proj", true);
        assertThat(exit).isZero();
        assertThat(serverRoot.resolve("proj")).isDirectory();
    }

    @Test
    @DisplayName("init with --bootstrap-remote creates nested remoteRoot when only parent exists")
    void init_bootstrapRemote_createsLeafUnderExistingParent(@TempDir Path clientRoot) throws IOException {
        Files.createDirectories(serverRoot.resolve("sftp"));
        int exit = runInit(clientRoot, "/sftp/myproject", true);
        assertThat(exit).isZero();
        assertThat(serverRoot.resolve("sftp").resolve("myproject")).isDirectory();
    }

    @Test
    @DisplayName("init with --bootstrap-remote fails on bad password (exit 2 auth)")
    void init_bootstrapRemote_badPassword_authError(@TempDir Path clientRoot) {
        // El server espera "pass", le mandamos "wrong"
        StringWriter err = new StringWriter();
        int exit = new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(err))
            .execute("-C", clientRoot.toString(),
                "init", "--non-interactive",
                "--host", "127.0.0.1",
                "--port", String.valueOf(port),
                "--user", USER,
                "--password", "wrong-password",
                "--remote-root", "/proj",
                "--bootstrap-remote", "--insecure");

        assertThat(exit).isEqualTo(SftpErrors.EXIT_AUTH);
        // El config local debería haberse escrito igual (init no aborta antes
        // de probar la red).
        assertThat(clientRoot.resolve(".sync").resolve("config.json")).exists();
    }

    @Test
    @DisplayName("init with --bootstrap-remote fails fast on bad host (transport error)")
    void init_bootstrapRemote_unreachable_transportError(@TempDir Path clientRoot) {
        // Apuntamos a puerto 1 (cerrado en cualquier máquina razonable)
        StringWriter err = new StringWriter();
        int exit = new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(err))
            .execute("-C", clientRoot.toString(),
                "init", "--non-interactive",
                "--host", "127.0.0.1",
                "--port", "1",
                "--user", USER,
                "--password", PASS,
                "--remote-root", "/proj",
                "--bootstrap-remote", "--insecure");

        // Connection refused → I/O o transport (depende de qué subclase tira MINA)
        assertThat(exit).isIn(SftpErrors.EXIT_TRANSPORT, SftpErrors.EXIT_IO);
    }

    // ----- helpers -----

    private int runInit(Path clientRoot, String remoteRoot, boolean bootstrap) {
        var args = new java.util.ArrayList<String>();
        args.add("-C"); args.add(clientRoot.toString());
        args.add("init"); args.add("--non-interactive");
        args.add("--host"); args.add("127.0.0.1");
        args.add("--port"); args.add(String.valueOf(port));
        args.add("--user"); args.add(USER);
        args.add("--password"); args.add(PASS);
        args.add("--remote-root"); args.add(remoteRoot);
        if (bootstrap) {
            args.add("--bootstrap-remote");
            args.add("--insecure");
        }
        return new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args.toArray(new String[0]));
    }
}
