package io.github.shizuka.sftpsync;

import io.github.shizuka.sftpsync.cli.InitCommand;
import io.github.shizuka.sftpsync.cli.PullCommand;
import io.github.shizuka.sftpsync.cli.PushCommand;
import io.github.shizuka.sftpsync.cli.ResolveCommand;
import io.github.shizuka.sftpsync.cli.StatusCommand;
import io.github.shizuka.sftpsync.cli.WatchCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * Entry point del CLI.
 *
 * <p>Subcomandos:
 * <ul>
 *   <li>{@code init}    — inicializa una carpeta local enganchada a un remoto SFTP.</li>
 *   <li>{@code status}  — muestra el delta entre local, remoto y la base del último sync.</li>
 *   <li>{@code push}    — sube cambios locales al remoto.</li>
 *   <li>{@code pull}    — baja cambios remotos al disco local.</li>
 *   <li>{@code watch}   — proceso de fondo que mantiene el status fresco sin sincronizar.</li>
 *   <li>{@code resolve} — resuelve un conflicto post-pull.</li>
 * </ul>
 */
@Command(
    name = "sftp-sync",
    mixinStandardHelpOptions = true,
    version = "sftp-sync 0.1.0",
    description = "Sincronizador de carpetas multi-PC sobre SFTP, estilo Git.",
    subcommands = {
        InitCommand.class,
        StatusCommand.class,
        PushCommand.class,
        PullCommand.class,
        WatchCommand.class,
        ResolveCommand.class,
        CommandLine.HelpCommand.class
    }
)
public final class Main implements Runnable {

    @Option(
        names = {"-v", "--verbose"},
        description = "Logging detallado a stderr.",
        scope = ScopeType.INHERIT
    )
    boolean verbose;

    @Option(
        names = {"-C", "--directory"},
        description = "Carpeta del proyecto sftp-sync. Default: directorio actual.",
        defaultValue = ".",
        scope = ScopeType.INHERIT
    )
    String directory;

    @Override
    public void run() {
        // Sin subcomando: mostrar el help.
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
