package io.github.shizuka.sftpsync.manifest;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Snapshot completo del estado de una carpeta sincronizada.
 *
 * <p>Es el formato que viaja en {@code .sync/manifest.json} (local) y
 * {@code <remote>/.sync/manifest.json} (remoto). El compact constructor garantiza
 * que las entries quedan ordenadas alfabéticamente por path para que los diffs
 * sobre el JSON sean visualmente útiles.
 *
 * @param version       schema version (1).
 * @param generatedAt   ISO-8601 instant cuando se generó (informativo, no se compara).
 * @param generatedBy   {@code clientId} del cliente que lo escribió (informativo).
 * @param entries       map ordenado de path relativo → entry.
 */
public record Manifest(
    int version,
    String generatedAt,
    String generatedBy,
    Map<String, ManifestEntry> entries
) {

    /** Versión actual del schema del manifest. Bump al hacer cambios incompatibles. */
    public static final int CURRENT_VERSION = 1;

    public Manifest {
        if (version == 0) {
            version = CURRENT_VERSION;
        }
        if (entries == null || entries.isEmpty()) {
            entries = Collections.emptyMap();
        } else {
            // TreeMap → orden estable por path. Unmodifiable → seguridad de inmutabilidad.
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        }
    }

    /** Crea un manifest nuevo con timestamp actual. */
    public static Manifest of(String generatedBy, Map<String, ManifestEntry> entries) {
        return new Manifest(
            CURRENT_VERSION,
            Instant.now().toString(),
            generatedBy,
            entries
        );
    }

    /** Crea un manifest vacío (carpeta sin archivos). */
    public static Manifest empty(String generatedBy) {
        return of(generatedBy, Collections.emptyMap());
    }
}
