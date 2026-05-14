package io.github.shizuka.sftpsync.cli;

import org.apache.sshd.common.SshException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class SftpErrorsTest {

    private static int map(IOException e, StringWriter err) {
        return SftpErrors.mapToExitCode(e, new PrintWriter(err));
    }

    @Test
    @DisplayName("auth failure detected by exception class name → exit 2")
    void authByClassName_returnsExit2() {
        // Simulamos una AuthenticationException via clase anónima cuyo
        // getSimpleName contiene "Auth".
        IOException auth = new AuthenticationFailureException("boom");
        StringWriter err = new StringWriter();
        assertThat(map(auth, err)).isEqualTo(SftpErrors.EXIT_AUTH);
        assertThat(err.toString()).containsIgnoringCase("Autenticación");
    }

    @Test
    @DisplayName("auth detected by 'publickey' in message → exit 2")
    void authByPublickeyKeyword_returnsExit2() {
        IOException e = new IOException("publickey: no acceptable identity");
        StringWriter err = new StringWriter();
        assertThat(map(e, err)).isEqualTo(SftpErrors.EXIT_AUTH);
    }

    @Test
    @DisplayName("auth detected by 'permission denied' in cause chain → exit 2")
    void authInCauseChain_returnsExit2() {
        IOException root = new IOException("Permission denied (password)");
        IOException wrapped = new IOException("transport failure", root);
        StringWriter err = new StringWriter();
        assertThat(map(wrapped, err)).isEqualTo(SftpErrors.EXIT_AUTH);
    }

    @Test
    @DisplayName("SshException without auth keywords → exit 3 (transport)")
    void sshExceptionNoAuth_returnsExit3() {
        SshException e = new SshException("Connection refused");
        StringWriter err = new StringWriter();
        assertThat(map(e, err)).isEqualTo(SftpErrors.EXIT_TRANSPORT);
        assertThat(err.toString()).containsIgnoringCase("Error SSH");
    }

    @Test
    @DisplayName("plain IOException no auth keywords → exit 5 (IO)")
    void plainIoException_returnsExit5() {
        IOException e = new IOException("disk full");
        StringWriter err = new StringWriter();
        assertThat(map(e, err)).isEqualTo(SftpErrors.EXIT_IO);
        assertThat(err.toString()).containsIgnoringCase("conexión");
    }

    @Test
    @DisplayName("null cause chain doesn't crash")
    void nullCauseChain_safe() {
        IOException e = new IOException();
        StringWriter err = new StringWriter();
        assertThat(map(e, err)).isEqualTo(SftpErrors.EXIT_IO);
    }

    /**
     * Helper class para simular una excepción cuyo nombre contiene "Auth".
     * No usamos directamente las MINA exceptions porque cambian entre versiones.
     */
    private static final class AuthenticationFailureException extends IOException {
        AuthenticationFailureException(String msg) { super(msg); }
    }
}
