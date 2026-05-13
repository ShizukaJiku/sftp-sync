package io.github.shizuka.sftpsync.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathExpansionTest {

    @Test
    @DisplayName("expandTilde replaces ~ alone with user.home")
    void expandTilde_lonelyTilde_returnsUserHome() {
        assertThat(PathExpansion.expandTilde("~"))
            .isEqualTo(System.getProperty("user.home"));
    }

    @Test
    @DisplayName("expandTilde replaces ~/ prefix with user.home")
    void expandTilde_tildeSlashPrefix_replacesWithHome() {
        String home = System.getProperty("user.home");
        assertThat(PathExpansion.expandTilde("~/.ssh/id_ed25519"))
            .isEqualTo(home + "/.ssh/id_ed25519");
    }

    @Test
    @DisplayName("expandTilde leaves absolute paths untouched")
    void expandTilde_absolutePath_returnsUnchanged() {
        assertThat(PathExpansion.expandTilde("/absolute/path"))
            .isEqualTo("/absolute/path");
    }

    @Test
    @DisplayName("expandTilde leaves relative paths untouched")
    void expandTilde_relativePath_returnsUnchanged() {
        assertThat(PathExpansion.expandTilde("relative/path"))
            .isEqualTo("relative/path");
    }

    @Test
    @DisplayName("expandTilde leaves paths with embedded ~ untouched")
    void expandTilde_tildeInMiddle_returnsUnchanged() {
        assertThat(PathExpansion.expandTilde("/foo/~/bar"))
            .isEqualTo("/foo/~/bar");
    }

    @Test
    @DisplayName("expandTilde returns null for null input")
    void expandTilde_nullInput_returnsNull() {
        assertThat(PathExpansion.expandTilde(null)).isNull();
    }
}
