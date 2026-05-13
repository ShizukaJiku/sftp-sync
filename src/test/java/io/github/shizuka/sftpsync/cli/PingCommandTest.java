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
 * Smoke tests de PingCommand que NO requieren un servidor real.
 *
 * <p>El happy-path (conexión exitosa) se valida manualmente contra
 * emberstack/sftp con {@code sftp-sync ping}.
 */
class PingCommandTest {

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
    @DisplayName("ping fails with exit 1 when there is no config")
    void ping_noConfig_returnsExit1(@TempDir Path tmp) {
        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err).execute(
            "-C", tmp.toString(), "ping"
        );
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("init");
    }

    @Test
    @DisplayName("ping fails with non-zero exit when host is unreachable")
    void ping_closedPort_returnsNonZero(@TempDir Path tmp) throws IOException {
        // Setup: config con puerto cerrado y una "key" que existe pero no es real.
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");
        assertThat(runInit(tmp, "127.0.0.1", 1, fakeKey.toString())).isZero();

        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err)
            .execute("-C", tmp.toString(), "ping", "--insecure");

        // Puerto 1 cerrado → IOException al conectar → exit 5.
        assertThat(exit).isEqualTo(5);
        assertThat(err.toString()).containsIgnoringCase("conexión");
    }

    @Test
    @DisplayName("ping fails with exit 5 when key file does not exist")
    void ping_missingKeyFile_returnsExit5(@TempDir Path tmp) {
        // Init con keyPath a un archivo que no existe.
        assertThat(runInit(tmp, "127.0.0.1", 22, tmp.resolve("nope").toString()))
            .isZero();

        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err)
            .execute("-C", tmp.toString(), "ping", "--insecure");

        assertThat(exit).isEqualTo(5);
        assertThat(err.toString()).containsIgnoringCase("clave");
    }
}
