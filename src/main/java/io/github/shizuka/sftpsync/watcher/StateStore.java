package io.github.shizuka.sftpsync.watcher;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.config.SyncConfigStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Lectura y escritura de {@code .sync/state.json}.
 *
 * <p>Patrón estándar del proyecto: I/O atómica con tmp + ATOMIC_MOVE.
 */
public final class StateStore {

    public static final String FILE_NAME = "state.json";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private StateStore() {}

    public static Path path(Path projectRoot) {
        return projectRoot.resolve(SyncConfigStore.DIR_NAME).resolve(FILE_NAME);
    }

    /** Carga el estado, o {@code null} si no existe / es ilegible. */
    public static WatchState loadOrNull(Path projectRoot) {
        Path p = path(projectRoot);
        if (!Files.exists(p)) return null;
        try {
            return JSON.std.beanFrom(WatchState.class,
                Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Si el snapshot existe Y {@code lastRemoteCheckAt} es más reciente que
     * {@code now - 2 * pollIntervalSeconds}, retorna el estado.
     * Si está stale o falta, retorna {@code null}.
     */
    public static WatchState loadIfFresh(Path projectRoot, int pollIntervalSeconds) {
        WatchState s = loadOrNull(projectRoot);
        if (s == null) return null;
        try {
            Instant last = Instant.parse(s.lastRemoteCheckAt());
            Duration age = Duration.between(last, Instant.now());
            if (age.getSeconds() <= 2L * pollIntervalSeconds) return s;
        } catch (DateTimeParseException ignored) { /* trat como stale */ }
        return null;
    }

    public static void save(Path projectRoot, WatchState state) throws IOException {
        Path syncDir = projectRoot.resolve(SyncConfigStore.DIR_NAME);
        Files.createDirectories(syncDir);
        Path target = path(projectRoot);
        Path tmp = syncDir.resolve(FILE_NAME + ".tmp");

        String json = JSON_PRETTY.asString(state) + System.lineSeparator();
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
