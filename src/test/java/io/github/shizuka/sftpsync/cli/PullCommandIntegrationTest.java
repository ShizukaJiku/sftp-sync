package io.github.shizuka.sftpsync.cli;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.config.WatchConfig;
import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestEntry;
import io.github.shizuka.sftpsync.util.Hashing;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests del comando {@code pull} contra un MINA SSHD server
 * in-process. Valida el flujo end-to-end (handshake + SFTP read + ATOMIC_MOVE
 * + base.json update) y, sobre todo, el invariant crítico de
 * {@link PullCommand}: si CUALQUIER descarga falla, {@code base.json} NO se
 * actualiza.
 *
 * <p>El server se levanta en un puerto random en {@code 127.0.0.1}, con un
 * filesystem virtualizado sobre {@code serverRoot} y password auth fijo
 * (user/pass). Los tests son hermetic — no requieren red ni emberstack.
 */
class PullCommandIntegrationTest {

    private static final String USER = "user";
    private static final String PASS = "pass";
    private static final String CLIENT_ID = "test-client";

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
    @DisplayName("pull downloads all files and updates base.json on happy path")
    void pull_happyPath_downloadsAllAndUpdatesBase(@TempDir Path clientRoot) throws IOException {
        String remoteRoot = "/proj";
        writeClientConfig(clientRoot, remoteRoot);

        Map<String, byte[]> serverFiles = Map.of(
            "a.txt", "alpha".getBytes(StandardCharsets.UTF_8),
            "b.txt", "beta".getBytes(StandardCharsets.UTF_8),
            "sub/c.txt", "gamma gamma gamma".getBytes(StandardCharsets.UTF_8));
        seedServer(remoteRoot, serverFiles);

        int exit = runPull(clientRoot);

        assertThat(exit).isZero();
        for (var e : serverFiles.entrySet()) {
            Path local = clientRoot.resolve(e.getKey());
            assertThat(local).exists();
            assertThat(Files.readAllBytes(local)).isEqualTo(e.getValue());
        }
        // base.json se actualizó con el manifest remoto (mismas N entries).
        Path baseJson = clientRoot.resolve(".sync").resolve("base.json");
        assertThat(baseJson).exists();
        String baseContent = Files.readString(baseJson);
        assertThat(baseContent)
            .contains("a.txt")
            .contains("b.txt")
            .contains("sub/c.txt");
    }

    @Test
    @DisplayName("pull preserves base.json when a file is missing on server")
    void pull_fileMissingOnServer_baseNotUpdated(@TempDir Path clientRoot) throws IOException {
        String remoteRoot = "/proj";
        writeClientConfig(clientRoot, remoteRoot);

        // Manifest dice que existen 2 archivos, pero solo subimos UNO.
        byte[] aBytes = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = "beta".getBytes(StandardCharsets.UTF_8);
        Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        entries.put("a.txt", new ManifestEntry(Hashing.sha256(aBytes), aBytes.length));
        entries.put("b.txt", new ManifestEntry(Hashing.sha256(bBytes), bBytes.length));
        writeServerManifest(remoteRoot, entries);
        writeServerFile(remoteRoot, "a.txt", aBytes);
        // b.txt NO se crea en el server → la descarga fallará.

        int exit = runPull(clientRoot);

        assertThat(exit).isNotZero();
        // base.json NO debe existir (era el primer pull) o estar vacío.
        Path baseJson = clientRoot.resolve(".sync").resolve("base.json");
        if (Files.exists(baseJson)) {
            String content = Files.readString(baseJson);
            assertThat(content)
                .as("base.json no debe contener entries si el pull falló")
                .doesNotContain("a.txt");
        }
        // El archivo que sí estaba pudo haberse bajado parcialmente, pero el invariant
        // del test es solo sobre base.json — no asertamos sobre a.txt para mantener
        // el test focused.
    }

    @Test
    @DisplayName("pull preserves base.json when server returns content with mismatched hash")
    void pull_hashMismatch_baseNotUpdated(@TempDir Path clientRoot) throws IOException {
        String remoteRoot = "/proj";
        writeClientConfig(clientRoot, remoteRoot);

        // Manifest dice que a.txt tiene hash de "expected", pero subimos bytes de "tampered".
        byte[] expectedBytes = "expected".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBytes = "TAMPERED".getBytes(StandardCharsets.UTF_8);
        Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        entries.put("a.txt",
            new ManifestEntry(Hashing.sha256(expectedBytes), expectedBytes.length));
        writeServerManifest(remoteRoot, entries);
        writeServerFile(remoteRoot, "a.txt", tamperedBytes);

        int exit = runPull(clientRoot);

        assertThat(exit).isNotZero();
        Path baseJson = clientRoot.resolve(".sync").resolve("base.json");
        if (Files.exists(baseJson)) {
            assertThat(Files.readString(baseJson)).doesNotContain("a.txt");
        }
    }

    @Test
    @DisplayName("pull with --workers 1 still completes the happy path")
    void pull_singleWorker_completesHappyPath(@TempDir Path clientRoot) throws IOException {
        String remoteRoot = "/proj";
        writeClientConfig(clientRoot, remoteRoot);
        seedServer(remoteRoot, Map.of(
            "only.txt", "x".getBytes(StandardCharsets.UTF_8)));

        int exit = runPullWithWorkers(clientRoot, 1);

        assertThat(exit).isZero();
        assertThat(clientRoot.resolve("only.txt")).exists();
    }

    @Test
    @DisplayName("pull rejects --workers 0")
    void pull_workersZero_fails(@TempDir Path clientRoot) throws IOException {
        writeClientConfig(clientRoot, "/proj");
        StringWriter err = new StringWriter();
        int exit = new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(err))
            .execute("-C", clientRoot.toString(), "pull", "--insecure", "--workers", "0");
        assertThat(exit).isEqualTo(2);
        assertThat(err.toString()).contains("--workers");
    }

    // ----- helpers -----

    private void writeClientConfig(Path clientRoot, String remoteRoot) throws IOException {
        Files.createDirectories(clientRoot.resolve(".sync"));
        SyncConfig cfg = new SyncConfig(
            CLIENT_ID,
            new RemoteConfig("127.0.0.1", port, USER, null, remoteRoot, PASS),
            SyncConfig.defaultIgnorePatterns(),
            true,
            200,
            WatchConfig.defaults()
        );
        SyncConfigStore.save(clientRoot, cfg);
    }

    private void seedServer(String remoteRoot, Map<String, byte[]> files) throws IOException {
        Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        for (var e : files.entrySet()) {
            entries.put(e.getKey(), new ManifestEntry(Hashing.sha256(e.getValue()), e.getValue().length));
            writeServerFile(remoteRoot, e.getKey(), e.getValue());
        }
        writeServerManifest(remoteRoot, entries);
    }

    private void writeServerFile(String remoteRoot, String relPath, byte[] bytes) throws IOException {
        // remoteRoot empieza con "/"; VirtualFileSystemFactory ya routea "/" a serverRoot.
        Path target = serverRoot.resolve(stripLeadingSlash(remoteRoot)).resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private void writeServerManifest(String remoteRoot, Map<String, ManifestEntry> entries)
            throws IOException {
        Manifest m = Manifest.of(UUID.randomUUID().toString(), entries);
        Path manifestPath = serverRoot
            .resolve(stripLeadingSlash(remoteRoot))
            .resolve(".sync")
            .resolve("manifest.json");
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath,
            JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(m) + "\n",
            StandardCharsets.UTF_8);
    }

    private static String stripLeadingSlash(String p) {
        return p.startsWith("/") ? p.substring(1) : p;
    }

    private int runPull(Path clientRoot) {
        return runPullWithWorkers(clientRoot, 4);
    }

    private int runPullWithWorkers(Path clientRoot, int workers) {
        return new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute("-C", clientRoot.toString(), "pull", "--insecure",
                "--workers", String.valueOf(workers));
    }
}
