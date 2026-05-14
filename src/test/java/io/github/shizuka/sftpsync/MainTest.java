package io.github.shizuka.sftpsync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test del esqueleto del CLI: verifica que picocli arme la jerarquía de
 * comandos correctamente y que --help produzca output reconocible.
 */
class MainTest {

    @Test
    void helpListsAllSubcommands() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        CommandLine cmd = new CommandLine(new Main())
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(err));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString())
            .contains("sftp-sync")
            .contains("init")
            .contains("status")
            .contains("push")
            .contains("pull")
            .contains("watch")
            .contains("resolve");
    }

    @Test
    void unknownSubcommandFails() {
        CommandLine cmd = new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()));

        int exitCode = cmd.execute("not-a-real-command");

        assertThat(exitCode).isNotZero();
    }

    @Test
    void statusSubcommandFailsWithoutConfig() {
        // Sin .sync/config.json status devuelve exit 1 con un mensaje claro
        // sugiriendo correr 'init'.
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(err));

        int exitCode = cmd.execute("status");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).containsIgnoringCase("init");
    }

    @Test
    @DisplayName("isMinaShutdownNoise silences post-close executor error from MINA")
    void isMinaShutdownNoise_minaIllegalStateAfterClose_returnsTrue() {
        // Reproducimos la excepción concreta que MINA tira en Windows tras
        // SshClient.stop(): IllegalStateException con el mensaje exacto y
        // al menos un frame en org.apache.sshd.*.
        IllegalStateException t = new IllegalStateException("Executor has been shut down");
        t.setStackTrace(new StackTraceElement[] {
            new StackTraceElement(
                "org.apache.sshd.common.util.ValidateUtils", "throwIllegalStateException",
                "ValidateUtils.java", 228),
            new StackTraceElement(
                "org.apache.sshd.common.util.threads.NoCloseExecutor", "execute",
                "NoCloseExecutor.java", 100),
            new StackTraceElement(
                "sun.nio.ch.WindowsAsynchronousSocketChannelImpl$ReadTask", "failed",
                "WindowsAsynchronousSocketChannelImpl.java", 587),
        });

        assertThat(Main.isMinaShutdownNoise(t)).isTrue();
    }

    @Test
    @DisplayName("isMinaShutdownNoise lets through non-sshd executor errors")
    void isMinaShutdownNoise_unrelatedIllegalState_returnsFalse() {
        // Mismo mensaje pero stack sin frame de sshd → NO silenciar, podría
        // ser un bug genuino en otra parte del sistema.
        IllegalStateException t = new IllegalStateException("Executor has been shut down");
        t.setStackTrace(new StackTraceElement[] {
            new StackTraceElement(
                "com.example.MyExecutor", "submit", "MyExecutor.java", 42),
        });

        assertThat(Main.isMinaShutdownNoise(t)).isFalse();
    }

    @Test
    @DisplayName("isMinaShutdownNoise ignores other exception types even with sshd in stack")
    void isMinaShutdownNoise_otherExceptionType_returnsFalse() {
        // NPE con stack en sshd no debería silenciarse — es un bug genuino.
        NullPointerException t = new NullPointerException("oops");
        t.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("org.apache.sshd.foo.Bar", "baz", "Bar.java", 10),
        });

        assertThat(Main.isMinaShutdownNoise(t)).isFalse();
    }

    @Test
    @DisplayName("isMinaShutdownNoise tolerates null message")
    void isMinaShutdownNoise_nullMessage_returnsFalse() {
        IllegalStateException t = new IllegalStateException((String) null);
        assertThat(Main.isMinaShutdownNoise(t)).isFalse();
    }
}
