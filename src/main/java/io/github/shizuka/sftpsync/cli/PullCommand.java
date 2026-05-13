package io.github.shizuka.sftpsync.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Baja cambios remotos al disco local.
 *
 * <p>Flujo: scan local → bajar manifest remoto → three-way diff → para cada archivo
 * no-conflictivo, descargar a {@code <path>.tmp} con verificación de hash y
 * {@code Files.move(ATOMIC_MOVE)}. Conflictos quedan como {@code <path>.remote}.
 */
@Command(
    name = "pull",
    description = "Bajar cambios remotos. Conflictos se marcan, no se aplican."
)
public final class PullCommand implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Mostrar qué se bajaría sin tocar el disco local.", defaultValue = "false")
    boolean dryRun;

    @Override
    public Integer call() {
        // TODO: implementar paso 9 del plan.
        System.out.println("[pull] not yet implemented");
        return 0;
    }
}
