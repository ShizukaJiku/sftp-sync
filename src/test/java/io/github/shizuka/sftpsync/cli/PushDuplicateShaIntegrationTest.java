package io.github.shizuka.sftpsync.cli;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.config.WatchConfig;
import io.github.shizuka.sftpsync.manifest.Manifest;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de regresión para el bug crítico encontrado en producción:
 * cuando dos archivos del changeset tienen contenido idéntico (mismo SHA-256),
 * el push reporta "upload X" para ambos pero solo UNO termina en su destino.
 *
 * <p>Causa: {@code Map<staged, finalRemote>} usaba el staging path (que es
 * content-addressed por SHA) como key. Con SHAs duplicados, el segundo
 * {@code put} sobreescribía el primer {@code finalRemote} — y solo se hacía
 * un único {@code posix-rename}.
 *
 * <p>Casos típicos en proyectos reales:
 * <ul>
 *   <li>Archivos vacíos (todos comparten SHA del empty file).</li>
 *   <li>Placeholders ({@code secret-db-password}, etc.) con mismo contenido.</li>
 *   <li>{@code CODEOWNERS}, boilerplates de CI, etc. copiados entre proyectos.</li>
 * </ul>
 *
 * <p>El fix mantiene una {@code List<Promotion>} (no Map) y, en la fase de
 * promote, si el staging fue consumido por un rename previo (otro file con
 * mismo SHA), re-uploadea desde local antes de promover el destino actual.
 */
class PushDuplicateShaIntegrationTest {

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
    @DisplayName("push of two files with identical content delivers BOTH to their final paths")
    void push_twoFilesIdenticalContent_bothEndUpOnServer(@TempDir Path clientRoot) throws IOException {
        String remoteRoot = "/proj";
        writeClientConfig(clientRoot, remoteRoot);

        // Mismo contenido en dos paths distintos → mismo SHA-256 → colisión
        // garantizada en el Map<staged, finalRemote> del bug original.
        byte[] sharedContent = "shared-content-by-both".getBytes(StandardCharsets.UTF_8);
        Files.write(clientRoot.resolve("alpha.txt"), sharedContent);
        Files.createDirectories(clientRoot.resolve("nested"));
        Files.write(clientRoot.resolve("nested/beta.txt"), sharedContent);

        int exit = runPush(clientRoot);

        assertThat(exit).isZero();
        // Pre-fix: solo UNO de estos archivos existía en el server.
        // Post-fix: AMBOS están en su destino.
        Path serverAlpha = serverRoot.resolve("proj/alpha.txt");
        Path serverBeta = serverRoot.resolve("proj/nested/beta.txt");
        assertThat(serverAlpha).exists();
        assertThat(serverBeta).exists();
        assertThat(Files.readAllBytes(serverAlpha)).isEqualTo(sharedContent);
        assertThat(Files.readAllBytes(serverBeta)).isEqualTo(sharedContent);
    }

    @Test
    @DisplayName("push of multiple empty files (all share SHA of empty) delivers all of them")
    void push_multipleEmptyFiles_allDelivered(@TempDir Path clientRoot) throws IOException {
        String remoteRoot = "/proj";
        writeClientConfig(clientRoot, remoteRoot);

        // El caso real del user: muchos archivos vacíos como placeholders.
        // SHA del empty file es siempre el mismo, así que TODOS colisionaban.
        for (String name : List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")) {
            Files.createFile(clientRoot.resolve(name));
        }

        int exit = runPush(clientRoot);

        assertThat(exit).isZero();
        for (String name : List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")) {
            Path serverFile = serverRoot.resolve("proj/" + name);
            assertThat(serverFile)
                .as("Empty file '%s' debe existir en el server", name)
                .exists();
            assertThat(Files.size(serverFile)).isZero();
        }
    }

    @Test
    @DisplayName("push of three files where two share content and one is unique")
    void push_mixedDuplicatesAndUnique_allDelivered(@TempDir Path clientRoot) throws IOException {
        String remoteRoot = "/proj";
        writeClientConfig(clientRoot, remoteRoot);

        byte[] shared = "shared".getBytes(StandardCharsets.UTF_8);
        byte[] unique = "unique".getBytes(StandardCharsets.UTF_8);
        Files.write(clientRoot.resolve("dup1.txt"), shared);
        Files.write(clientRoot.resolve("dup2.txt"), shared);
        Files.write(clientRoot.resolve("uniq.txt"), unique);

        int exit = runPush(clientRoot);

        assertThat(exit).isZero();
        assertThat(serverRoot.resolve("proj/dup1.txt")).exists();
        assertThat(serverRoot.resolve("proj/dup2.txt")).exists();
        assertThat(serverRoot.resolve("proj/uniq.txt")).exists();
        assertThat(Files.readAllBytes(serverRoot.resolve("proj/dup1.txt"))).isEqualTo(shared);
        assertThat(Files.readAllBytes(serverRoot.resolve("proj/dup2.txt"))).isEqualTo(shared);
        assertThat(Files.readAllBytes(serverRoot.resolve("proj/uniq.txt"))).isEqualTo(unique);
    }

    // ----- helpers -----

    private void writeClientConfig(Path clientRoot, String remoteRoot) throws IOException {
        Files.createDirectories(clientRoot.resolve(".sync"));
        SyncConfig cfg = new SyncConfig(
            CLIENT_ID,
            new RemoteConfig("127.0.0.1", port, USER, null, remoteRoot, PASS),
            SyncConfig.defaultIgnorePatterns(),
            200,
            WatchConfig.defaults()
        );
        SyncConfigStore.save(clientRoot, cfg);
    }

    private int runPush(Path clientRoot) {
        return new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute("-C", clientRoot.toString(), "push", "--insecure");
    }

    // Sanity check helper (unused but documents the JSON shape expectation).
    @SuppressWarnings("unused")
    private Manifest readServerManifest(String remoteRoot) throws IOException {
        Path manifestPath = serverRoot
            .resolve(remoteRoot.startsWith("/") ? remoteRoot.substring(1) : remoteRoot)
            .resolve(".sync").resolve("manifest.json");
        return JSON.std.beanFrom(Manifest.class,
            Files.readString(manifestPath, StandardCharsets.UTF_8));
    }
}
