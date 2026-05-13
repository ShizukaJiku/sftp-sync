package io.github.shizuka.sftpsync.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Muestra el delta entre local, remoto y la base del último sync.
 *
 * <p>Si el watcher está corriendo, lee del cache {@code .sync/state.json} (instantáneo).
 * Si no, hace un scan ad-hoc.
 */
@Command(
    name = "status",
    description = "Mostrar qué cambió localmente, remotamente, y conflictos pendientes."
)
public final class StatusCommand implements Callable<Integer> {

    @Option(names = "--no-cache", description = "Forzar scan en vez de leer el cache del watcher.", defaultValue = "false")
    boolean noCache;

    @Override
    public Integer call() {
        // TODO: implementar paso 7 del plan.
        System.out.println("[status] not yet implemented");
        return 0;
    }
}
