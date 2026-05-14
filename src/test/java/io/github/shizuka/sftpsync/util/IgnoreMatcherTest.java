package io.github.shizuka.sftpsync.util;

import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.WatchConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoreMatcherTest {

    // ---- Casos básicos ----

    @Test
    @DisplayName("empty patterns list matches nothing")
    void matches_emptyPatterns_matchesNothing() {
        IgnoreMatcher m = new IgnoreMatcher(List.of());
        assertThat(m.matches("anything")).isFalse();
        assertThat(m.matches("a/b/c.txt")).isFalse();
    }

    @Test
    @DisplayName("directory prefix pattern matches files inside that directory at any depth")
    void matches_directoryPrefix_matchesContents() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("target/"));
        assertThat(m.matches("target/foo.class")).isTrue();
        assertThat(m.matches("target/sub/Bar.class")).isTrue();
        assertThat(m.matches("module-a/target/foo.class")).isTrue();
        assertThat(m.matches("src/Main.java")).isFalse();
        assertThat(m.matches("target.md")).isFalse();
    }

    @Test
    @DisplayName("glob suffix *.ext matches by extension at any level")
    void matches_globSuffix_matchesByExtension() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("*.class"));
        assertThat(m.matches("Foo.class")).isTrue();
        assertThat(m.matches("path/to/Bar.class")).isTrue();
        assertThat(m.matches("Foo.java")).isFalse();
    }

    @Test
    @DisplayName("multiple patterns work as logical OR")
    void matches_multiplePatterns_usesOr() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("target/", "*.log", ".sync/"));
        assertThat(m.matches("target/x.class")).isTrue();
        assertThat(m.matches("logs/app.log")).isTrue();
        assertThat(m.matches(".sync/config.json")).isTrue();
        assertThat(m.matches("src/Main.java")).isFalse();
    }

    @Test
    @DisplayName("blank and whitespace-only patterns are ignored")
    void matches_blankPattern_ignored() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("", "  "));
        assertThat(m.matches("anything")).isFalse();
    }

    // ---- Comentarios y escapes ----

    @Test
    @DisplayName("# starts a comment, ignored")
    void matches_comment_ignored() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("# this is a comment", "*.log"));
        assertThat(m.matches("# this is a comment")).isFalse();
        assertThat(m.matches("foo.log")).isTrue();
    }

    @Test
    @DisplayName("\\# escapes the comment marker for files literally starting with #")
    void matches_escapedHash_literalHash() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("\\#important"));
        assertThat(m.matches("#important")).isTrue();
        assertThat(m.matches("important")).isFalse();
    }

    // ---- Negaciones ----

    @Test
    @DisplayName("! negates a previous ignore (last match wins)")
    void matches_negation_unignoresLater() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("*.log", "!important.log"));
        assertThat(m.matches("foo.log")).isTrue();
        assertThat(m.matches("important.log")).isFalse();
    }

    @Test
    @DisplayName("negation order matters: !foo before *.log re-ignores foo.log")
    void matches_negation_orderMatters() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("!important.log", "*.log"));
        assertThat(m.matches("important.log")).isTrue();
    }

    // ---- Anclaje ----

    @Test
    @DisplayName("leading / anchors pattern to project root")
    void matches_leadingSlash_anchorsToRoot() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("/target/"));
        assertThat(m.matches("target/foo.class")).isTrue();
        assertThat(m.matches("module-a/target/foo.class")).isFalse();
    }

    @Test
    @DisplayName("pattern with / in middle is anchored to root")
    void matches_midSlash_anchoredToRoot() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("src/secret.txt"));
        assertThat(m.matches("src/secret.txt")).isTrue();
        assertThat(m.matches("other/src/secret.txt")).isFalse();
    }

    // ---- Globs avanzados ----

    @Test
    @DisplayName("? matches a single character (not /)")
    void matches_questionMark_singleChar() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("file?.txt"));
        assertThat(m.matches("file1.txt")).isTrue();
        assertThat(m.matches("fileA.txt")).isTrue();
        assertThat(m.matches("file10.txt")).isFalse();
    }

    @Test
    @DisplayName("**/ matches zero or more directory components")
    void matches_doubleStarSlash_zeroOrMore() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("**/build"));
        assertThat(m.matches("build")).isTrue();
        assertThat(m.matches("a/build")).isTrue();
        assertThat(m.matches("a/b/c/build")).isTrue();
    }

    @Test
    @DisplayName("foo/** matches everything under foo")
    void matches_trailingDoubleStar_everythingUnder() {
        IgnoreMatcher m = new IgnoreMatcher(List.of("foo/**"));
        assertThat(m.matches("foo/bar")).isTrue();
        assertThat(m.matches("foo/bar/baz.txt")).isTrue();
        assertThat(m.matches("other/foo/bar")).isFalse();
    }

    // ---- Carga desde .syncignore ----

    @Test
    @DisplayName("fromConfig reads .syncignore if file exists")
    void fromConfig_readsSyncignore(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve(".syncignore"),
            "# build artifacts\n"
            + "target/\n"
            + "*.class\n"
            + "!important.class\n");
        SyncConfig cfg = baseConfig();

        IgnoreMatcher m = IgnoreMatcher.fromConfig(tmp, cfg);

        assertThat(m.matches("target/foo")).isTrue();
        assertThat(m.matches("Foo.class")).isTrue();
        assertThat(m.matches("important.class")).isFalse();
        assertThat(m.matches("src/Main.java")).isFalse();
    }

    @Test
    @DisplayName("fromConfig ignores .gitignore — it is no longer consulted")
    void fromConfig_ignoresGitignoreFile(@TempDir Path tmp) throws IOException {
        // Histórico: sftp-sync leía .gitignore. Ya no. Solo .syncignore aplica.
        Files.writeString(tmp.resolve(".gitignore"), "*.class\n");
        SyncConfig cfg = baseConfig();

        IgnoreMatcher m = IgnoreMatcher.fromConfig(tmp, cfg);

        // El *.class de .gitignore no debe bloquear: NO se lee ese archivo.
        assertThat(m.matches("Foo.class")).isFalse();
    }

    @Test
    @DisplayName("fromConfig works without a .syncignore file")
    void fromConfig_noSyncignoreFile(@TempDir Path tmp) throws IOException {
        SyncConfig cfg = baseConfig();
        IgnoreMatcher m = IgnoreMatcher.fromConfig(tmp, cfg);
        // Solo los patterns de config aplican.
        assertThat(m.matches("target/foo")).isTrue();
        assertThat(m.matches("anything-else")).isFalse();
    }

    private static SyncConfig baseConfig() {
        return new SyncConfig(
            "client-id",
            new RemoteConfig("h", 22, "u", "/k", "/r", null),
            List.of("target/"),
            200,
            new WatchConfig(0, 0)
        );
    }
}
