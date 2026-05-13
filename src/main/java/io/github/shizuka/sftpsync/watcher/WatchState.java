package io.github.shizuka.sftpsync.watcher;

import java.util.List;

/**
 * Snapshot de {@code .sync/state.json} — lo que el watcher publica para que
 * {@code sftp-sync status} sea instantáneo.
 *
 * <p>Si {@code lastRemoteCheckAt} es más viejo que {@code 2 * pollIntervalSeconds},
 * {@code status} ignora el snapshot y hace un scan ad-hoc.
 *
 * @param lastRemoteCheckAt ISO-8601 instant del último poll exitoso del remoto.
 * @param lastLocalScanAt   ISO-8601 instant del último scan local.
 * @param summary           contadores agregados del último diff.
 * @param remoteReachable   {@code true} si el último intento de poll funcionó.
 * @param errors            mensajes recientes (errores de red, etc.).
 */
public record WatchState(
    String lastRemoteCheckAt,
    String lastLocalScanAt,
    Summary summary,
    boolean remoteReachable,
    List<String> errors
) {

    public WatchState {
        if (summary == null) summary = new Summary(0, 0, 0);
        if (errors == null) errors = List.of();
        else errors = List.copyOf(errors);
    }

    /**
     * Conteos agregados que muestra {@code status}.
     *
     * @param localChanged  archivos que el cliente modificó/agregó desde la base.
     * @param remoteChanged archivos que cambiaron en el remoto desde la base.
     * @param conflicts     archivos con cambios incompatibles en ambos lados.
     */
    public record Summary(int localChanged, int remoteChanged, int conflicts) {}
}
