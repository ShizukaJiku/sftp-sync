package io.github.shizuka.sftpsync.config;

import java.util.List;
import java.util.UUID;

/**
 * Raíz del archivo {@code .sync/config.json}. Inmutable.
 *
 * <p>Cada PC cliente tiene uno. Identifica el remoto, las opciones del cliente,
 * y el {@code clientId} único que se usa en logs/locks remotos para distinguir
 * qué máquina hizo qué.
 *
 * <p>El formato JSON es deliberadamente plano y legible para que el usuario pueda
 * editarlo a mano si quiere ajustar {@code ignore}, intervalos, etc.
 *
 * <p>El compact constructor aplica defaults a campos faltantes (típicamente
 * porque el archivo se escribió con una versión vieja del schema) y hace una
 * copia defensiva de la lista de patrones {@code ignore}.
 */
public record SyncConfig(
    String clientId,
    RemoteConfig remote,
    List<String> ignore,
    boolean useGitignore,
    int maxFileSizeMB,
    WatchConfig watch
) {
    public SyncConfig {
        if (clientId == null || clientId.isBlank()) {
            clientId = UUID.randomUUID().toString();
        }
        if (ignore == null) {
            ignore = defaultIgnorePatterns();
        } else {
            ignore = List.copyOf(ignore);
        }
        if (maxFileSizeMB == 0) {
            maxFileSizeMB = 200;
        }
        if (watch == null) {
            watch = WatchConfig.defaults();
        }
    }

    /**
     * Construye una config con valores por defecto sensatos y un {@code clientId}
     * recién generado. El bloque {@code remote} queda con campos nulos y debe
     * completarse antes de hacer cualquier operación de red.
     */
    public static SyncConfig withDefaults() {
        return new SyncConfig(
            UUID.randomUUID().toString(),
            RemoteConfig.of(null, null, null, null),
            defaultIgnorePatterns(),
            true,
            200,
            WatchConfig.defaults()
        );
    }

    /** Patrones a ignorar por defecto, útiles para proyectos típicos. */
    public static List<String> defaultIgnorePatterns() {
        return List.of(
            ".sync/",
            ".git/",
            "target/",
            "build/",
            "node_modules/",
            ".idea/",
            ".vscode/",
            "*.class",
            "*.log"
        );
    }
}
