package io.github.shizuka.sftpsync;

import io.github.shizuka.sftpsync.cli.InitCommand;
import io.github.shizuka.sftpsync.cli.LockCommand;
import io.github.shizuka.sftpsync.cli.PingCommand;
import io.github.shizuka.sftpsync.cli.PullCommand;
import io.github.shizuka.sftpsync.cli.PushCommand;
import io.github.shizuka.sftpsync.cli.RemoteManifestCommand;
import io.github.shizuka.sftpsync.cli.ResolveCommand;
import io.github.shizuka.sftpsync.cli.ScanCommand;
import io.github.shizuka.sftpsync.cli.StatusCommand;
import io.github.shizuka.sftpsync.cli.WatchCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
        ScanCommand.class,
        PingCommand.class,
        RemoteManifestCommand.class,
        LockCommand.class,
        CommandLine.HelpCommand.class
    }
)
public final class Main implements Runnable {

    /** Logging detallado a stderr. Heredado por subcomandos vía {@code @ParentCommand}. */
    @Option(
        names = {"-v", "--verbose"},
        description = "Logging detallado a stderr.",
        scope = ScopeType.INHERIT
    )
    public boolean verbose;

    /** Carpeta raíz del proyecto sftp-sync. Subcomandos la leen vía {@code @ParentCommand}. */
    @Option(
        names = {"-C", "--directory"},
        description = "Carpeta del proyecto sftp-sync. Default: directorio actual.",
        defaultValue = ".",
        scope = ScopeType.INHERIT
    )
    public String directory;

    @Override
    public void run() {
        // Sin subcomando: mostrar el help.
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        // Forzar UTF-8 en stdout/stderr para que las tildes y eñes salgan bien
        // en Windows PowerShell por default (que usa CP1252) y otras terminales
        // que no tienen UTF-8 como charset nativo. Independiente del locale del SO.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
