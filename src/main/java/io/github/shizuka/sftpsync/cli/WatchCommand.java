package io.github.shizuka.sftpsync.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Proceso de fondo que vigila la carpeta local y poll del remoto.
 *
 * <p>NO sincroniza automáticamente. Solo mantiene {@code .sync/state.json} fresco para
 * que {@code sftp-sync status} sea instantáneo.
 */
@Command(
    name = "watch",
    description = "Vigilar local y remoto, mantener el status fresco. No sincroniza."
)
public final class WatchCommand implements Callable<Integer> {

    @Option(names = "--poll-interval",
            description = "Segundos entre polls del remote manifest. Default: 30.",
            defaultValue = "30")
    int pollIntervalSeconds;

    @Override
    public Integer call() {
        // TODO: implementar paso 13 del plan.
        System.out.println("[watch] not yet implemented");
        return 0;
    }
}
