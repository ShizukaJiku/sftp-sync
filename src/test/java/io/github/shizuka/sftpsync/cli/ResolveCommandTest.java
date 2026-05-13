package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.manifest.BaseStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
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

class ResolveCommandTest {

    private static CommandLine newCli(StringWriter out, StringWriter err) {
        return new CommandLine(new Main())
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(err));
    }

    private static void initProject(Path tmp) throws IOException {
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");
        int exit = newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--host", "127.0.0.1", "--port", "22",
            "--user", "u", "--key", fakeKey.toString(),
            "--remote-root", "/r"
        );
        assertThat(exit).isZero();
    }

    @Test
    @DisplayName("resolve --keep-local deletes the .remote and clears base entry")
    void resolve_keepLocal_deletesRemote(@TempDir Path tmp) throws IOException {
        initProject(tmp);
        Files.writeString(tmp.resolve("shared.txt"), "local version");
        Files.writeString(tmp.resolve("shared.txt.remote"), "remote version");

        StringWriter out = new StringWriter();
        int exit = newCli(out, new StringWriter()).execute(
            "-C", tmp.toString(), "resolve", "shared.txt", "--keep-local"
        );

        assertThat(exit).isZero();
        assertThat(Files.exists(tmp.resolve("shared.txt"))).isTrue();
        assertThat(Files.exists(tmp.resolve("shared.txt.remote"))).isFalse();
        assertThat(out.toString()).contains("keep-local");
    }

    @Test
    @DisplayName("resolve --keep-remote replaces local with remote and updates base")
    void resolve_keepRemote_replacesLocal(@TempDir Path tmp) throws IOException {
        initProject(tmp);
        Files.writeString(tmp.resolve("shared.txt"), "local version");
        Files.writeString(tmp.resolve("shared.txt.remote"), "remote version");

        int exit = newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(), "resolve", "shared.txt", "--keep-remote"
        );

        assertThat(exit).isZero();
        assertThat(Files.readString(tmp.resolve("shared.txt"))).isEqualTo("remote version");
        assertThat(Files.exists(tmp.resolve("shared.txt.remote"))).isFalse();

        // Base ahora tiene una entry para shared.txt (próximo status la verá sincronizada).
        Manifest base = BaseStore.loadOrEmpty(tmp);
        assertThat(base.entries()).containsKey("shared.txt");
    }

    @Test
    @DisplayName("resolve --keep-both renames local and promotes remote")
    void resolve_keepBoth_renamesAndPromotes(@TempDir Path tmp) throws IOException {
        initProject(tmp);
        Files.writeString(tmp.resolve("shared.txt"), "local version");
        Files.writeString(tmp.resolve("shared.txt.remote"), "remote version");

        int exit = newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(), "resolve", "shared.txt", "--keep-both"
        );

        assertThat(exit).isZero();
        // shared.txt ahora contiene la remota.
        assertThat(Files.readString(tmp.resolve("shared.txt"))).isEqualTo("remote version");
        // El .remote ya no existe.
        assertThat(Files.exists(tmp.resolve("shared.txt.remote"))).isFalse();
        // La local fue renombrada a shared.txt.local-<host>.
        try (var stream = Files.list(tmp)) {
            assertThat(stream
                .filter(p -> p.getFileName().toString().startsWith("shared.txt.local-"))
                .count())
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("resolve fails with exit 4 when there is no .remote file")
    void resolve_noRemoteFile_returnsExit4(@TempDir Path tmp) throws IOException {
        initProject(tmp);
        Files.writeString(tmp.resolve("shared.txt"), "local only");

        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err).execute(
            "-C", tmp.toString(), "resolve", "shared.txt", "--keep-local"
        );

        assertThat(exit).isEqualTo(4);
        assertThat(err.toString()).containsIgnoringCase(".remote");
    }

    @Test
    @DisplayName("resolve requires exactly one --keep-* flag")
    void resolve_requiresStrategy(@TempDir Path tmp) throws IOException {
        initProject(tmp);
        Files.writeString(tmp.resolve("shared.txt.remote"), "x");

        int exit = newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(), "resolve", "shared.txt"
        );
        assertThat(exit).isEqualTo(2); // picocli usage error for missing required @ArgGroup
    }
}
