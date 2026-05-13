package io.github.shizuka.sftpsync;

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
    void statusSubcommandRunsWithoutError() {
        CommandLine cmd = new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()));

        int exitCode = cmd.execute("status");

        assertThat(exitCode).isZero();
    }
}
