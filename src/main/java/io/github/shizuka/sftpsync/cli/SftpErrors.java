package io.github.shizuka.sftpsync.cli;

import org.apache.sshd.common.SshException;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Mapea {@link IOException} de MINA SSHD a exit codes consistentes en los
 * comandos CLI que abren una sesión SFTP.
 *
 * <p>Estrategia:
 * <ol>
 *   <li>Caminar la cadena de causas buscando un nombre de clase que indique
 *       autenticación ({@code *Auth*Exception}). MINA tiene varias subclases
 *       ({@code AuthenticationFailureException}, etc.) en distintas versiones,
 *       así que matcheamos por el nombre simple y no por una clase específica.</li>
 *   <li>Como fallback más débil, buscar tokens conocidos en el mensaje
 *       (mismas palabras en inglés en todas las locales que MINA usa).</li>
 *   <li>Si la excepción raíz es {@link SshException}, asumimos transporte.</li>
 *   <li>Sino, IO genérico.</li>
 * </ol>
 */
public final class SftpErrors {

    private SftpErrors() {}

    public static final int EXIT_AUTH = 2;
    public static final int EXIT_TRANSPORT = 3;
    public static final int EXIT_IO = 5;

    public static int mapToExitCode(IOException e, PrintWriter err) {
        if (isAuthFailure(e)) {
            err.println("Autenticación falló: " + e.getMessage());
            return EXIT_AUTH;
        }
        if (e instanceof SshException) {
            err.println("Error SSH: " + e.getMessage());
            return EXIT_TRANSPORT;
        }
        err.println("Error de conexión: " + e.getMessage());
        return EXIT_IO;
    }

    /**
     * {@code true} si la cadena de causas sugiere un fallo de autenticación.
     * Hace dos pasadas: primero por nombre de clase (más confiable), después
     * por mensaje (heurística de fallback).
     */
    private static boolean isAuthFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.contains("Auth")) return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) continue;
            String lower = msg.toLowerCase();
            if (lower.contains("auth") || lower.contains("password")
                || lower.contains("publickey") || lower.contains("permission denied")) {
                return true;
            }
        }
        return false;
    }
}
