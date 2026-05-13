package io.github.shizuka.sftpsync.manifest;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.config.SyncConfigStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lectura y escritura de {@code .sync/scancache.json}.
 *
 * <p>Mismo patrón que {@link SyncConfigStore}: serializar a String y escribir
 * todo de una vez con tmp + ATOMIC_MOVE.
 */
public final class ScanCacheStore {

    public static final String FILE_NAME = "scancache.json";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private ScanCacheStore() {}

    public static Path path(Path projectRoot) {
        return projectRoot.resolve(SyncConfigStore.DIR_NAME).resolve(FILE_NAME);
    }

    /**
     * Carga el scancache desde disco. Si el archivo no existe o está corrupto,
     * devuelve un cache vacío (la corrupción no es fatal: solo perdés el speed-up
     * del cache, el próximo scan rehashea todo).
     */
    public static ScanCache loadOrEmpty(Path projectRoot) {
        Path p = path(projectRoot);
        if (!Files.exists(p)) {
            return new ScanCache();
        }
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            ScanCacheFile file = JSON.std.beanFrom(ScanCacheFile.class, json);
            return new ScanCache(file.entries());
        } catch (IOException e) {
            // Cache corrupto → empezar de cero. No fatal.
            return new ScanCache();
        }
    }

    /**
     * Persiste el snapshot a {@code .sync/scancache.json}, SOLO si el cache fue
     * modificado desde la última persistencia. Los callers que llaman {@code save}
     * en un hot loop (ej. watcher cada segundo) ahorran I/O cuando nada cambió.
     *
     * @return {@code true} si efectivamente se escribió el archivo.
     */
    public static boolean save(Path projectRoot, ScanCache cache) throws IOException {
        if (!cache.isDirty()) return false;
        Path syncDir = projectRoot.resolve(SyncConfigStore.DIR_NAME);
        Files.createDirectories(syncDir);
        Path target = path(projectRoot);
        Path tmp = syncDir.resolve(FILE_NAME + ".tmp");

        ScanCacheFile snapshot = ScanCacheFile.of(cache.snapshot());
        String json = JSON_PRETTY.asString(snapshot) + System.lineSeparator();
        Files.writeString(tmp, json, StandardCharsets.UTF_8);

        try {
            Files.move(tmp, target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        cache.clearDirty();
        return true;
    }
}
