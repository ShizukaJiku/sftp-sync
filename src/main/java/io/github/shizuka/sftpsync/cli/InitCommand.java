package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.config.WatchConfig;
import io.github.shizuka.sftpsync.util.PathExpansion;
import io.github.shizuka.sftpsync.util.PathValidation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Inicializa una carpeta local enganchándola a un remoto SFTP.
 *
 * <p>Crea {@code .sync/config.json} con los datos de conexión y un {@code clientId}
 * único. Si los flags de conexión vienen por línea de comandos los usa directamente;
 * si faltan y hay TTY, los pregunta interactivamente.
 *
 * <p><b>Autenticación:</b> exactamente uno de {@code --key} o {@code --password}
 * debe estar presente (el método interactivo te pregunta cuál usar). El password,
 * si se usa, queda en plain text en {@code .sync/config.json} — NO commitear esa
 * carpeta a git, y considerar {@code chmod 600}.
 *
 * <p><b>Esta versión NO toca el SFTP todavía.</b> El primer push/pull contra el
 * remoto se hace en push y pull.
 */
@Command(
    name = "init",
    description = "Inicializar la carpeta actual conectándola a un remoto SFTP."
)
public final class InitCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Option(names = "--host",        description = "Host del servidor SFTP.")
    String host;

    @Option(names = "--port",        description = "Puerto SFTP. Default 22.", defaultValue = "22")
    int port;

    @Option(names = "--user",        description = "Usuario SFTP.")
    String user;

    @Option(names = "--key",
            description = "Path a la clave privada SSH. Default ~/.ssh/id_ed25519. Mutuamente exclusivo con --password.")
    String keyPath;

    @Option(names = "--password",
            description = "Password SFTP. Mutuamente exclusivo con --key. AVISO: queda en plain text en config.json.")
    String password;

    @Option(names = "--remote-root",
            description = "Carpeta absoluta en el remoto (ej. /upload/proyecto). "
                + "Mutuamente exclusivo con --remote-parent.")
    String remoteRoot;

    @Option(names = "--remote-parent",
            description = "Carpeta padre absoluta en el remoto (ej. /sftp). "
                + "El nombre de la carpeta local actual se anexa para formar el "
                + "remoteRoot. Atajo de --remote-root cuando querés que el remoto "
                + "espeje el nombre del proyecto.")
    String remoteParent;

    @Option(names = "--force",       description = "Sobrescribir un .sync/config.json existente.")
    boolean force;

    @Option(names = "--non-interactive",
            description = "No prompts. Falla si falta algún valor obligatorio.")
    boolean nonInteractive;

    @Override
    public Integer call() {
        Path root = Path.of(parent.directory).toAbsolutePath().normalize();

        if (SyncConfigStore.exists(root) && !force) {
            err("Ya existe " + SyncConfigStore.configPath(root));
            err("Usá --force para sobrescribir.");
            return 1;
        }

        if (remoteRoot != null && !remoteRoot.isBlank()
            && remoteParent != null && !remoteParent.isBlank()) {
            err("Error: --remote-root y --remote-parent son mutuamente exclusivos. "
                + "Usá uno u otro.");
            return 2;
        }

        String resolvedHost;
        String resolvedUser;
        String resolvedRoot;
        String resolvedKey;
        String resolvedPassword;
        try {
            resolvedHost = ask("Host del SFTP", host, null);
            resolvedUser = ask("Usuario SFTP", user, null);
            if (remoteParent != null && !remoteParent.isBlank()) {
                resolvedRoot = resolveRootFromParent(remoteParent.trim(), root);
            } else {
                resolvedRoot = ask("Carpeta absoluta en el remoto", remoteRoot, null);
            }

            // Selección de método de autenticación.
            if (keyPath != null && !keyPath.isBlank()) {
                resolvedKey = PathExpansion.expandTilde(keyPath.trim());
                resolvedPassword = null;
            } else if (password != null && !password.isBlank()) {
                resolvedKey = null;
                resolvedPassword = password;
            } else if (nonInteractive) {
                err("Error: pasá --key o --password en modo --non-interactive.");
                return 2;
            } else {
                String method = askChoice("Método de auth", "K", "P", "K");
                if (method.equalsIgnoreCase("K")) {
                    resolvedKey = PathExpansion.expandTilde(
                        ask("Path a la clave SSH privada", null, "~/.ssh/id_ed25519"));
                    resolvedPassword = null;
                } else {
                    resolvedKey = null;
                    resolvedPassword = askSecret("Password SFTP");
                }
            }
        } catch (IllegalStateException e) {
            err("Error: " + e.getMessage());
            return 2;
        }

        if (port < 1 || port > 65535) {
            err("Puerto inválido: " + port);
            return 2;
        }
        if (!resolvedRoot.startsWith("/")) {
            err("La carpeta remota debe ser absoluta (empezar con '/'). Recibido: "
                + resolvedRoot);
            return 2;
        }

        SyncConfig config = new SyncConfig(
            UUID.randomUUID().toString(),
            new RemoteConfig(resolvedHost, port, resolvedUser, resolvedKey,
                             resolvedRoot, resolvedPassword),
            SyncConfig.defaultIgnorePatterns(),
            true,
            200,
            WatchConfig.defaults()
        );

        try {
            SyncConfigStore.save(root, config);
        } catch (IOException e) {
            err("Error escribiendo config: " + e.getMessage());
            return 3;
        }

        out("");
        out("OK. Config escrita en " + SyncConfigStore.configPath(root));
        out("clientId: " + config.clientId());
        if (resolvedPassword != null) {
            out("");
            out("AVISO: el password quedó en plain text en config.json.");
            out("Recomendado: chmod 600 " + SyncConfigStore.configPath(root));
        }
        out("");
        out("(Nota: el módulo SFTP no transfiere datos todavía. Probá la conexión");
        out(" con 'sftp-sync ping --insecure' para validarla.)");
        return 0;
    }

    /**
     * Devuelve el valor preset si no está vacío; si no, prompt interactivo.
     * Si {@code defaultIfEmpty} no es null, se ofrece al usuario y se usa si
     * solo presiona Enter.
     *
     * @throws IllegalStateException si no hay valor y no se puede preguntar.
     */
    private String ask(String prompt, String preset, String defaultIfEmpty) {
        if (preset != null && !preset.isBlank()) {
            return preset.trim();
        }
        if (nonInteractive) {
            if (defaultIfEmpty != null) return defaultIfEmpty;
            throw new IllegalStateException(
                "Falta valor para '" + prompt + "' (modo --non-interactive)");
        }
        Console console = System.console();
        if (console == null) {
            if (defaultIfEmpty != null) return defaultIfEmpty;
            throw new IllegalStateException(
                "No hay terminal interactivo y falta valor para '" + prompt + "'");
        }
        String suggestion = defaultIfEmpty != null ? " [" + defaultIfEmpty + "]" : "";
        String input = console.readLine("%s%s: ", prompt, suggestion);
        if (input == null || input.isBlank()) {
            if (defaultIfEmpty != null) return defaultIfEmpty;
            throw new IllegalStateException("Valor vacío para '" + prompt + "'");
        }
        return input.trim();
    }

    /** Pregunta una elección entre dos opciones (case-insensitive), con default. */
    private String askChoice(String prompt, String optionA, String optionB, String defaultChoice) {
        Console console = System.console();
        if (console == null) {
            return defaultChoice;
        }
        while (true) {
            String input = console.readLine(
                "%s [%s/%s] (default %s): ",
                prompt, optionA, optionB, defaultChoice);
            if (input == null || input.isBlank()) return defaultChoice;
            String normalized = input.trim();
            if (normalized.equalsIgnoreCase(optionA) || normalized.equalsIgnoreCase(optionB)) {
                return normalized;
            }
            console.printf("Respondé %s o %s.%n", optionA, optionB);
        }
    }

    /** Pregunta un valor secreto (sin echo a terminal). */
    private String askSecret(String prompt) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                "No hay terminal interactivo para pedir '" + prompt + "'");
        }
        char[] chars = console.readPassword("%s: ", prompt);
        if (chars == null || chars.length == 0) {
            throw new IllegalStateException("Password vacío");
        }
        return new String(chars);
    }

    /**
     * Resuelve {@code remoteRoot} a partir de {@code --remote-parent} + el nombre
     * de la carpeta local actual.
     *
     * <p>Validaciones:
     * <ul>
     *   <li>{@code parent} debe empezar con "/".</li>
     *   <li>El cwd debe tener un nombre extraíble (falla en {@code C:\\} y similares).</li>
     *   <li>El folder name debe ser válido como segmento de path Windows
     *       (rechaza {@code CON}, {@code PRN}, {@code AUX}, etc.).</li>
     * </ul>
     *
     * <p>Edge case: en Windows con UNC paths como {@code \\\\server\\share},
     * {@code getFileName} devuelve {@code share}, lo que es comportamiento
     * aceptable aunque atípico — el user lo verá en el resumen final.
     *
     * @throws IllegalStateException si alguna validación falla.
     */
    static String resolveRootFromParent(String parent, Path cwd) {
        if (!parent.startsWith("/")) {
            throw new IllegalStateException(
                "--remote-parent debe empezar con '/'. Recibido: " + parent);
        }
        Path folder = cwd.getFileName();
        if (folder == null) {
            throw new IllegalStateException(
                "No se puede inferir nombre de carpeta desde " + cwd
                    + " (ej. raíz del filesystem). Usá --remote-root explícito.");
        }
        String folderName = folder.toString();
        if (folderName.isBlank() || folderName.contains("/") || folderName.contains("\\")) {
            throw new IllegalStateException(
                "Nombre de carpeta inválido: '" + folderName + "'");
        }
        String issue = PathValidation.findWindowsIssue(folderName);
        if (issue != null) {
            throw new IllegalStateException(
                "Nombre de carpeta '" + folderName
                    + "' no es válido como segmento remoto: " + issue);
        }
        // Caso "/" (parent es la raíz): el remoteRoot es "/" + folderName, NO
        // "//folderName" — algunos servers SFTP tratan "//" como ruta distinta.
        if ("/".equals(parent)) {
            return "/" + folderName;
        }
        String trimmedParent = parent.endsWith("/")
            ? parent.substring(0, parent.length() - 1)
            : parent;
        return trimmedParent + "/" + folderName;
    }

    private static void out(String s) { System.out.println(s); }
    private static void err(String s) { System.err.println(s); }
}
