package io.github.shizuka.sftpsync.manifest;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.config.SyncConfigStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lectura y escritura de {@code .sync/base.json}.
 *
 * <p>El "base" representa el snapshot del remoto al momento del último sync exitoso
 * (push o pull). Es el ancla del three-way diff: lo que cambió desde acá es lo que
 * el cliente debe propagar.
 *
 * <p>Mismo schema que el manifest. Mismo patrón de I/O atómica que {@link ManifestStore}.
 * Si querés un manifest "default" para una carpeta recién initializada, usá
 * {@link Manifest#empty(String)}.
 */
public final class BaseStore {

    public static final String FILE_NAME = "base.json";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private BaseStore() {}

    public static Path path(Path projectRoot) {
        return projectRoot.resolve(SyncConfigStore.DIR_NAME).resolve(FILE_NAME);
    }

    public static boolean exists(Path projectRoot) {
        return Files.exists(path(projectRoot));
    }

    /**
     * Lee el base. Si no existe, retorna un {@link Manifest#empty} con clientId vacío —
     * representa "nunca sincronizamos, todos los archivos locales son nuevos".
     */
    public static Manifest loadOrEmpty(Path projectRoot) throws IOException {
        Path p = path(projectRoot);
        if (!Files.exists(p)) {
            return Manifest.empty("");
        }
        return JSON.std.beanFrom(Manifest.class,
            Files.readString(p, StandardCharsets.UTF_8));
    }

    /** Lee el base. Tira {@link NoSuchFileException} si no existe. */
    public static Manifest load(Path projectRoot) throws IOException {
        Path p = path(projectRoot);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(p.toString());
        }
        return JSON.std.beanFrom(Manifest.class,
            Files.readString(p, StandardCharsets.UTF_8));
    }

    public static void save(Path projectRoot, Manifest base) throws IOException {
        Path syncDir = projectRoot.resolve(SyncConfigStore.DIR_NAME);
        Files.createDirectories(syncDir);
        Path target = path(projectRoot);
        Path tmp = syncDir.resolve(FILE_NAME + ".tmp");

        String json = JSON_PRETTY.asString(base) + System.lineSeparator();
        Files.writeString(tmp, json, StandardCharsets.UTF_8);

        try {
            Files.move(tmp, target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
