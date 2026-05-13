package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.manifest.ManifestStore;
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

import static org.assertj.core.api.Assertions.assertThat;

class ScanCommandTest {

    private static CommandLine newCli(StringWriter out, StringWriter err) {
        return new CommandLine(new Main())
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(err));
    }

    private static int runInit(Path tmp) {
        return newCli(new StringWriter(), new StringWriter()).execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--host", "h", "--user", "u",
            "--key", "/k", "--remote-root", "/r"
        );
    }

    @Test
    @DisplayName("scan fails with exit 1 when there is no config")
    void scan_noConfig_returnsExit1(@TempDir Path tmp) {
        StringWriter err = new StringWriter();
        int exit = newCli(new StringWriter(), err).execute(
            "-C", tmp.toString(), "scan"
        );
        assertThat(exit).isEqualTo(1);
    }

    @Test
    @DisplayName("scan after init prints valid JSON manifest with file entries")
    void scan_withFiles_printsManifestJson(@TempDir Path tmp) throws IOException {
        assertThat(runInit(tmp)).isZero();
        Files.writeString(tmp.resolve("hello.txt"), "abc", StandardCharsets.UTF_8);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = newCli(out, err).execute("-C", tmp.toString(), "scan");

        assertThat(exit).isZero();
        String json = out.toString();
        assertThat(json).contains("\"entries\"");
        assertThat(json).contains("hello.txt");
        assertThat(json).contains(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("scan with --save persists the manifest to .sync/manifest.json")
    void scan_withSaveFlag_writesManifestFile(@TempDir Path tmp) throws IOException {
        assertThat(runInit(tmp)).isZero();
        Files.writeString(tmp.resolve("a.txt"), "abc", StandardCharsets.UTF_8);

        int exit = newCli(new StringWriter(), new StringWriter())
            .execute("-C", tmp.toString(), "scan", "--save");

        assertThat(exit).isZero();
        assertThat(ManifestStore.exists(tmp)).isTrue();
        assertThat(ManifestStore.load(tmp).entries()).containsKey("a.txt");
    }

    @Test
    @DisplayName("scan respects ignore patterns from config (e.g. .sync/ excluded)")
    void scan_syncDirIgnoredByDefault(@TempDir Path tmp) throws IOException {
        assertThat(runInit(tmp)).isZero();
        Files.writeString(tmp.resolve("real.txt"), "x", StandardCharsets.UTF_8);

        StringWriter out = new StringWriter();
        newCli(out, new StringWriter()).execute("-C", tmp.toString(), "scan");

        // .sync/config.json existe pero no debe aparecer en el manifest.
        assertThat(out.toString()).contains("real.txt");
        assertThat(out.toString()).doesNotContain("config.json");
    }
}
