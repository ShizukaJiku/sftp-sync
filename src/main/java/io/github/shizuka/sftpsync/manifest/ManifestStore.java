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
 * Lectura y escritura del manifest local en {@code .sync/manifest.json}.
 *
 * <p>El manifest remoto vive en {@code <remote>/.sync/manifest.json} y se maneja
 * desde el módulo SFTP (próximos pasos), no acá.
 */
public final class ManifestStore {

    public static final String FILE_NAME = "manifest.json";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private ManifestStore() {}

    public static Path path(Path projectRoot) {
        return projectRoot.resolve(SyncConfigStore.DIR_NAME).resolve(FILE_NAME);
    }

    public static boolean exists(Path projectRoot) {
        return Files.exists(path(projectRoot));
    }

    public static Manifest load(Path projectRoot) throws IOException {
        Path p = path(projectRoot);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(p.toString());
        }
        String json = Files.readString(p, StandardCharsets.UTF_8);
        return JSON.std.beanFrom(Manifest.class, json);
    }

    public static void save(Path projectRoot, Manifest manifest) throws IOException {
        Path syncDir = projectRoot.resolve(SyncConfigStore.DIR_NAME);
        Files.createDirectories(syncDir);
        Path target = path(projectRoot);
        Path tmp = syncDir.resolve(FILE_NAME + ".tmp");

        String json = JSON_PRETTY.asString(manifest) + System.lineSeparator();
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
