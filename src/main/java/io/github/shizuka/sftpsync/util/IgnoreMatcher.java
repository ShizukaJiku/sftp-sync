package io.github.shizuka.sftpsync.util;

import io.github.shizuka.sftpsync.config.SyncConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Matcher con soporte completo de patrones estilo {@code .gitignore}.
 *
 * <p>Reglas soportadas:
 * <ul>
 *   <li><b>Comentarios</b>: línea empezando con {@code #} se ignora.</li>
 *   <li><b>Blanks</b>: líneas vacías se ignoran.</li>
 *   <li><b>Negación</b>: prefijo {@code !} des-ignora un path que matcheaba antes
 *       (último match wins).</li>
 *   <li><b>Escapes</b>: {@code \#} y {@code \!} matchean los caracteres literales.</li>
 *   <li><b>Anclaje a la raíz</b>: pattern con {@code /} en medio o al inicio se
 *       ancla a la raíz del proyecto. Sin {@code /} (o solo trailing) matchea a
 *       cualquier nivel.</li>
 *   <li><b>Solo directorios</b>: trailing {@code /} matchea archivos bajo ese
 *       directorio (a cualquier nivel si no está anclado).</li>
 *   <li><b>Globs</b>: {@code *} matchea cualquier secuencia sin cruzar {@code /};
 *       {@code ?} matchea un carácter; {@code **} matchea cero o más directorios.</li>
 * </ul>
 *
 * <p>Los paths que entran al matcher deben estar normalizados con forward-slash
 * (sin importar el OS) y sin leading {@code /}.
 */
public final class IgnoreMatcher {

    private final List<Rule> rules;

    public IgnoreMatcher(List<String> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        List<Rule> compiled = new ArrayList<>(patterns.size());
        for (String raw : patterns) {
            Rule r = compile(raw);
            if (r != null) compiled.add(r);
        }
        this.rules = List.copyOf(compiled);
    }

    /** Nombre del archivo de patrones del proyecto. Estilo {@code .gitignore}. */
    public static final String SYNCIGNORE_FILE = ".syncignore";

    /**
     * Construye un matcher combinando {@code config.ignore()} con el contenido de
     * {@code .syncignore} en la raíz si existe.
     *
     * <p>{@code .syncignore} es el archivo de patrones del proyecto, estilo
     * {@code .gitignore}. Es propio de sftp-sync y deliberadamente independiente
     * de {@code .gitignore}: lo que ignorás en git no necesariamente coincide con
     * lo que querés excluir del sync (típico: artefactos buildables que SÍ querés
     * sincronizar entre PCs, o secretos locales que NO querés subir).
     *
     * <p>Si no hay {@code .syncignore}, usa solo {@code config.ignore()} (los
     * defaults built-in tipo {@code .git/}, {@code target/}, etc).
     */
    public static IgnoreMatcher fromConfig(Path projectRoot, SyncConfig config) throws IOException {
        List<String> all = new ArrayList<>(config.ignore());
        Path syncignore = projectRoot.resolve(SYNCIGNORE_FILE);
        if (Files.isRegularFile(syncignore)) {
            all.addAll(Files.readAllLines(syncignore, StandardCharsets.UTF_8));
        }
        return new IgnoreMatcher(all);
    }

    /**
     * @param relativePath path relativo a la raíz del proyecto, separadores
     *                     forward-slash, sin leading {@code /}.
     * @return {@code true} si el path queda ignorado tras aplicar todas las reglas
     *         (la negación es last-wins, como en {@code .gitignore} real).
     */
    public boolean matches(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        boolean ignored = false;
        for (Rule rule : rules) {
            if (rule.regex.matcher(relativePath).matches()) {
                ignored = !rule.negate;
            }
        }
        return ignored;
    }

    // --- Parser ---

    private record Rule(Pattern regex, boolean negate) {}

    /** Compila una línea de patrón a una {@link Rule}, o {@code null} si la línea no aporta. */
    private static Rule compile(String raw) {
        if (raw == null) return null;
        String line = stripTrailingUnescapedSpaces(raw);
        if (line.isEmpty()) return null;
        if (line.startsWith("#")) return null;

        boolean negate = false;
        if (line.startsWith("!")) {
            negate = true;
            line = line.substring(1);
        } else if (line.startsWith("\\#") || line.startsWith("\\!")) {
            line = line.substring(1); // escape: tratar # o ! como literal
        }
        if (line.isEmpty()) return null;

        boolean directoryOnly = line.endsWith("/");
        if (directoryOnly) line = line.substring(0, line.length() - 1);

        // Anclaje: si contiene un '/' que no sea trailing, está anclado a la raíz.
        // Si no, matchea a cualquier nivel.
        boolean anchored = line.startsWith("/") || line.contains("/");
        if (line.startsWith("/")) line = line.substring(1);

        String regex = globToRegex(line, anchored, directoryOnly);
        return new Rule(Pattern.compile(regex), negate);
    }

    /**
     * Quita espacios trailing que NO estén escapados con {@code \}. Conserva
     * espacios intermedios y escapeados ({@code "\ "}).
     */
    private static String stripTrailingUnescapedSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            int backslashes = 0;
            int i = end - 2;
            while (i >= 0 && s.charAt(i) == '\\') { backslashes++; i--; }
            if (backslashes % 2 == 1) break; // espacio escapado
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Convierte un glob estilo gitignore (sin el {@code /} de anclaje ni trailing,
     * ya quitados por el caller) en una regex que matchea un path completo.
     */
    private static String globToRegex(String glob, boolean anchored, boolean directoryOnly) {
        StringBuilder sb = new StringBuilder();

        // Prefijo: si no está anclado, permitir cualquier prefijo de directorios.
        if (!anchored) {
            sb.append("(?:.*/)?");
        }

        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        // ** secuencia
                        boolean followedBySlash = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                        boolean precededBySlash = i > 0 && glob.charAt(i - 1) == '/';
                        if (followedBySlash) {
                            // "**/" — cero o más segmentos seguidos de /
                            sb.append("(?:.*/)?");
                            i += 3;
                        } else if (precededBySlash || i == 0) {
                            // "/**" final o "**" suelto: cualquier cosa
                            sb.append(".*");
                            i += 2;
                        } else {
                            // "**" pegado a otros chars: tratar como dos *
                            sb.append("[^/]*[^/]*");
                            i += 2;
                        }
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> { sb.append("[^/]"); i++; }
                case '.', '(', ')', '+', '|', '^', '$', '{', '}' -> {
                    sb.append('\\').append(c); i++;
                }
                case '\\' -> {
                    if (i + 1 < glob.length()) {
                        char next = glob.charAt(i + 1);
                        // Escape regex-safe del char siguiente.
                        sb.append('\\').append(next);
                        i += 2;
                    } else {
                        sb.append("\\\\");
                        i++;
                    }
                }
                default -> { sb.append(c); i++; }
            }
        }

        // Suffix: directoryOnly significa que el pattern matchea archivos bajo ese path.
        // Si no es directoryOnly, también admitimos archivos dentro (gitignore así lo hace).
        sb.append(directoryOnly ? "/.*" : "(?:/.*)?");

        return sb.toString();
    }
}
