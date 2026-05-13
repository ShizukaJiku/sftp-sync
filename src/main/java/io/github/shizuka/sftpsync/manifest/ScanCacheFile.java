package io.github.shizuka.sftpsync.manifest;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Snapshot inmutable del scancache para persistir a disco. Wrapper alrededor
 * del Map que agrega un {@code version} para futuras migraciones de schema.
 */
public record ScanCacheFile(int version, Map<String, ScanCacheEntry> entries) {

    public static final int CURRENT_VERSION = 1;

    public ScanCacheFile {
        if (version == 0) {
            version = CURRENT_VERSION;
        }
        if (entries == null || entries.isEmpty()) {
            entries = Collections.emptyMap();
        } else {
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        }
    }

    public static ScanCacheFile of(Map<String, ScanCacheEntry> entries) {
        return new ScanCacheFile(CURRENT_VERSION, entries);
    }

    public static ScanCacheFile empty() {
        return new ScanCacheFile(CURRENT_VERSION, Collections.emptyMap());
    }
}
