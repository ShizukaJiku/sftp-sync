package io.github.shizuka.sftpsync.cli;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Resuelve un conflicto detectado por {@code pull} eligiendo qué versión queda.
 */
@Command(
    name = "resolve",
    description = "Resolver un conflicto post-pull eligiendo qué versión queda."
)
public final class ResolveCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path del archivo en conflicto (relativo a la raíz).")
    String path;

    @ArgGroup(multiplicity = "1")
    Strategy strategy;

    static class Strategy {
        @Option(names = "--keep-local",  description = "Mantener la versión local, descartar la remota.")
        boolean keepLocal;

        @Option(names = "--keep-remote", description = "Sobrescribir local con la versión remota.")
        boolean keepRemote;

        @Option(names = "--keep-both",
                description = "Mantener ambas: rename local a <path>.local-<host>, remote a <path>.")
        boolean keepBoth;
    }

    @Override
    public Integer call() {
        // TODO: implementar paso 12 del plan.
        System.out.println("[resolve " + path + "] not yet implemented");
        return 0;
    }
}
