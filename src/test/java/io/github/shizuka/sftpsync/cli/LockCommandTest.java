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
 * Smoke tests de LockCommand que NO requieren un servidor real.
 *
 * <p>El happy-path (acquire/release/read) se valida manualmente contra
 * emberstack/sftp con {@code sftp-sync lock [--acquire push|--release]}.
 */
class LockCommandTest {

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
    @DisplayName("lock fails with exit 1 when there is no config")
    void lock_noConfig_returnsExit1(@TempDir Path tmp) {
        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err)
            .execute("-C", tmp.toString(), "lock");
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("init");
    }

    @Test
    @DisplayName("lock fails with exit 5 when host is unreachable (read mode)")
    void lock_read_closedPort_returnsExit5(@TempDir Path tmp) throws IOException {
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");
        assertThat(runInit(tmp, "127.0.0.1", 1, fakeKey.toString())).isZero();

        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err)
            .execute("-C", tmp.toString(), "lock", "--insecure");

        assertThat(exit).isNotZero();
    }

    @Test
    @DisplayName("lock rejects --acquire and --release together")
    void lock_acquireAndRelease_isUsageError(@TempDir Path tmp) throws IOException {
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");
        assertThat(runInit(tmp, "127.0.0.1", 1, fakeKey.toString())).isZero();

        // picocli @ArgGroup(exclusive=true) marca esto como usage error → exit 2.
        int exit = newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(),
            "lock", "--acquire", "push", "--release", "--insecure"
        );
        assertThat(exit).isEqualTo(2);
    }

    @Test
    @DisplayName("lock --acquire fails non-zero when host is unreachable")
    void lock_acquire_closedPort_returnsNonZero(@TempDir Path tmp) throws IOException {
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");
        assertThat(runInit(tmp, "127.0.0.1", 1, fakeKey.toString())).isZero();

        int exit = newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(),
            "lock", "--acquire", "push", "--insecure"
        );
        assertThat(exit).isNotZero();
    }
}
