package io.github.shizuka.sftpsync.config;

/**
 * Parámetros del modo {@code watch}. Inmutable.
 *
 * <p>El compact constructor aplica defaults sensatos cuando los valores vienen
 * en cero (típicamente porque el JSON no los traía).
 */
public record WatchConfig(int pollIntervalSeconds, int debounceMs) {

    public WatchConfig {
        if (pollIntervalSeconds == 0) {
            pollIntervalSeconds = 30;
        }
        if (debounceMs == 0) {
            debounceMs = 500;
        }
    }

    /** Devuelve una instancia con los defaults sensatos. */
    public static WatchConfig defaults() {
        return new WatchConfig(30, 500);
    }
}
