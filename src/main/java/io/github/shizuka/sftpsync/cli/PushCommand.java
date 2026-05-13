package io.github.shizuka.sftpsync.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Sube cambios locales al remoto SFTP.
 *
 * <p>Flujo: scan local → adquirir lock remoto → bajar manifest remoto → three-way diff →
 * abortar si hay conflictos → subir archivos a {@code .sync/staging/} → atomic rename →
 * subir manifest nuevo → liberar lock → actualizar {@code base.json}.
 */
@Command(
    name = "push",
    description = "Subir cambios locales al remoto. Aborta si hay conflictos."
)
public final class PushCommand implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Mostrar qué se subiría sin tocar el remoto.", defaultValue = "false")
    boolean dryRun;

    @Option(names = "--gc", description = "Limpiar archivos huérfanos en .sync/staging/ del remoto.", defaultValue = "false")
    boolean gc;

    @Override
    public Integer call() {
        // TODO: implementar paso 8 del plan.
        System.out.println("[push] not yet implemented");
        return 0;
    }
}
