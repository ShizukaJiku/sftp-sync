package io.github.shizuka.sftpsync.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathValidationTest {

    @Test
    @DisplayName("normal path returns null (no issue)")
    void normalPath_noIssue() {
        assertThat(PathValidation.findWindowsIssue("src/main/Foo.java")).isNull();
        assertThat(PathValidation.findWindowsIssue("README.md")).isNull();
    }

    @Test
    @DisplayName("reserved name CON is rejected")
    void reservedName_con_rejected() {
        assertThat(PathValidation.findWindowsIssue("CON")).contains("reservado");
        assertThat(PathValidation.findWindowsIssue("CON.txt")).contains("reservado");
        assertThat(PathValidation.findWindowsIssue("sub/PRN.log")).contains("reservado");
    }

    @Test
    @DisplayName("reserved name is case-insensitive")
    void reservedName_caseInsensitive() {
        assertThat(PathValidation.findWindowsIssue("con")).contains("reservado");
        assertThat(PathValidation.findWindowsIssue("Lpt1.dat")).contains("reservado");
    }

    @Test
    @DisplayName("trailing space rejected")
    void trailingSpace_rejected() {
        assertThat(PathValidation.findWindowsIssue("file ")).contains("espacio");
    }

    @Test
    @DisplayName("trailing dot rejected")
    void trailingDot_rejected() {
        assertThat(PathValidation.findWindowsIssue("file.")).contains("punto");
    }

    @Test
    @DisplayName("colon in segment rejected")
    void colon_rejected() {
        assertThat(PathValidation.findWindowsIssue("foo:bar")).contains("prohibido");
    }

    @Test
    @DisplayName("path over MAX_PATH characters rejected")
    void overLength_rejected() {
        String tooLong = "a/".repeat(150) + "file";
        assertThat(PathValidation.findWindowsIssue(tooLong)).contains("longitud");
    }

    @Test
    @DisplayName("path with internal spaces is ok")
    void internalSpace_ok() {
        assertThat(PathValidation.findWindowsIssue("My Documents/file.txt")).isNull();
    }

    @Test
    @DisplayName("file named like reserved is rejected even nested")
    void nestedReserved_rejected() {
        assertThat(PathValidation.findWindowsIssue("src/COM1")).contains("reservado");
    }
}
