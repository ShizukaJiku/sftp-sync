package io.github.shizuka.sftpsync.util;

import java.util.Set;

/**
 * Validaciones de paths cross-platform que pueden bloquear archivos al
 * sincronizar a Windows.
 *
 * <p>Si querés llevar tu sync entre Linux/macOS y Windows, ciertos nombres son
 * inválidos en Windows aunque el manifest los acepte. Este helper detecta esos
 * problemas en el lado emisor (Linux/macOS) para que el destinatario (Windows)
 * no falle al hacer pull.
 *
 * <p>Reglas chequeadas:
 * <ul>
 *   <li>Longitud > 260 caracteres (límite clásico MAX_PATH).</li>
 *   <li>Nombre reservado: {@code CON, PRN, AUX, NUL, COM1-COM9, LPT1-LPT9}
 *       (case-insensitive, con o sin extensión).</li>
 *   <li>Caracteres prohibidos: {@code < > : " | ? *} y bytes de control.</li>
 *   <li>Componentes terminados en espacio o punto.</li>
 * </ul>
 */
public final class PathValidation {

    private static final int MAX_PATH = 260;

    private static final Set<String> RESERVED_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private PathValidation() {}

    /**
     * Retorna un mensaje de error si el path es inválido para Windows, o
     * {@code null} si está OK. El path se asume normalizado con forward-slash.
     */
    public static String findWindowsIssue(String relativePath) {
        if (relativePath.length() > MAX_PATH) {
            return "longitud " + relativePath.length() + " > " + MAX_PATH;
        }
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty()) continue;
            String issue = checkSegment(segment);
            if (issue != null) return "segmento '" + segment + "': " + issue;
        }
        return null;
    }

    private static String checkSegment(String segment) {
        // Reserved names (case-insensitive, con o sin extensión)
        int dot = segment.indexOf('.');
        String stem = dot < 0 ? segment : segment.substring(0, dot);
        if (RESERVED_NAMES.contains(stem.toUpperCase())) {
            return "nombre reservado en Windows";
        }

        // Trailing space/dot
        char last = segment.charAt(segment.length() - 1);
        if (last == ' ' || last == '.') {
            return "termina en espacio o punto";
        }

        // Caracteres prohibidos
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 0x20 || c == '<' || c == '>' || c == ':' || c == '"'
                || c == '|' || c == '?' || c == '*') {
                return "carácter prohibido en Windows: '" + c + "'";
            }
        }
        return null;
    }
}
