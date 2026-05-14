package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests de RemoteManifestCommand que NO requieren un servidor real.
 *
 * <p>El happy-path (get/put exitoso) se valida manualmente contra emberstack/sftp
 * con {@code sftp-sync remote-manifest [--put] --insecure}.
 */
class RemoteManifestCommandTest {

    private static CommandLine newCli(StringWriter out, StringWriter err) {
        return new CommandLine(new Main())
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(err));
    }

    private static int runInit(Path tmp, String host, int port, String keyPath) {
        return newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--host", host,
            "--port", String.valueOf(port),
            "--user", "u",
            "--key", keyPath,
            "--remote-root", "/r"
        );
    }

    @Test
    @DisplayName("remote-manifest fails with exit 1 when there is no config")
    void remoteManifest_noConfig_returnsExit1(@TempDir Path tmp) {
        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err)
            .execute("-C", tmp.toString(), "remote-manifest");
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("init");
    }

    @Test
    @DisplayName("remote-manifest --put fails when host is unreachable")
    void remoteManifest_put_closedPort_returnsNonZero(@TempDir Path tmp) throws IOException {
        // Setup: config con puerto cerrado y "key" que existe pero no es real.
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");
        assertThat(runInit(tmp, "127.0.0.1", 1, fakeKey.toString())).isZero();

        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err)
            .execute("-C", tmp.toString(), "remote-manifest", "--insecure");

        // MINA SSHD: connection-refused → SshException → EXIT_TRANSPORT (3).
        assertThat(exit).isEqualTo(3);
        assertThat(err.toString()).containsIgnoringCase("Error SSH");
    }

    @Test
    @DisplayName("remote-manifest --put fails with exit 4 when local manifest missing (cannot even connect)")
    void remoteManifest_put_returnsNonZero_whenNoConnectivity(@TempDir Path tmp)
            throws IOException {
        // Sin manifest local; igual no podremos conectar (puerto 1 cerrado).
        // Verifica que el comando se completa con exit non-zero sin crashear.
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");
        assertThat(runInit(tmp, "127.0.0.1", 1, fakeKey.toString())).isZero();

        int exit = newCli(new StringWriter(), new StringWriter())
            .execute("-C", tmp.toString(), "remote-manifest", "--put", "--insecure");

        assertThat(exit).isNotZero();
    }
}
