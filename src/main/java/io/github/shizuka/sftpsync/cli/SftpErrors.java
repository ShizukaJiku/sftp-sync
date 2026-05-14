package io.github.shizuka.sftpsync.cli;

import org.apache.sshd.common.SshException;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Mapea {@link IOException} de MINA SSHD a exit codes consistentes.
 */
public final class SftpErrors {

    private SftpErrors() {}

    public static final int EXIT_AUTH = 2;
    public static final int EXIT_TRANSPORT = 3;
    public static final int EXIT_IO = 5;

    public static int mapToExitCode(IOException e, PrintWriter err) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("auth") || lower.contains("password")
                    || lower.contains("publickey") || lower.contains("permission denied")) {
                    err.println("Autenticación falló: " + e.getMessage());
                    return EXIT_AUTH;
                }
            }
            cause = cause.getCause();
        }
        if (e instanceof SshException) {
            err.println("Error SSH: " + e.getMessage());
            return EXIT_TRANSPORT;
        }
        err.println("Error de conexión: " + e.getMessage());
        return EXIT_IO;
    }
}
