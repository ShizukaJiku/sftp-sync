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
import io.github.shizuka.sftpsync.util.ConsoleEncoding;
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
    version = "sftp-sync 1.0.3",
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
        // En Windows, decirle a la consola que renderice como UTF-8 (CP 65001)
        // ANTES de imprimir nada. Sin esto, los bytes UTF-8 que escribimos en
        // setOut/setErr se renderizan según el code page nativo de la terminal
        // (CP437/CP1252 según locale) → mojibake en tildes y eñes. No-op en
        // Linux y macOS (sus terminales ya son UTF-8).
        ConsoleEncoding.enableUtf8OnWindows();

        // Forzar UTF-8 en stdout/stderr para que los bytes que escribimos
        // representen correctamente los caracteres no-ASCII. Independiente del
        // locale del SO. Combinado con enableUtf8OnWindows, garantiza render
        // correcto en cualquier terminal Windows/Linux/macOS.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        Thread.setDefaultUncaughtExceptionHandler(Main::handleUncaught);

        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    /**
     * Handler global que silencia ruido cosmético de MINA SSHD en Windows tras
     * cerrar la sesión, y delega cualquier otra excepción al comportamiento
     * default de la JVM.
     *
     * <p><b>Por qué existe:</b> en Windows, después de {@code SshClient.stop()},
     * el IOCP del kernel todavía puede tener una read pendiente sobre el socket
     * SSH. Cuando esa read falla (porque cerramos el socket), un thread del pool
     * de IOCP intenta despachar el callback a través de {@code NoCloseExecutor}
     * de MINA, que ya está apagado. Resultado: {@code IllegalStateException:
     * Executor has been shut down} en un thread de fondo, después de que el
     * comando ya terminó OK. La excepción es post-mortem y no afecta corrección,
     * solo ensucia stderr.
     *
     * <p>Condiciones para silenciar (todas deben cumplirse):
     * <ol>
     *   <li>Es {@code IllegalStateException}.</li>
     *   <li>El mensaje contiene {@code "Executor has been shut down"}.</li>
     *   <li>Algún frame del stack tiene clase que empieza con {@code org.apache.sshd.}</li>
     * </ol>
     *
     * <p>Si no cumple, replicamos el comportamiento default (imprimir el stack
     * a stderr con el prefix estándar). El handler tiene try/catch propio: si
     * fallara, no queremos enmascarar el problema.
     *
     * <p><b>Nota:</b> este handler es process-global. Si algún día este código se
     * usara como librería embedida, sobrescribiría el handler del host.
     */
    private static void handleUncaught(Thread thread, Throwable t) {
        try {
            if (isMinaShutdownNoise(t)) return;
            System.err.print("Exception in thread \"" + thread.getName() + "\" ");
            t.printStackTrace(System.err);
        } catch (Throwable inner) {
            // Si el handler propio falla, al menos no perder el original.
            try {
                System.err.println("Uncaught exception handler failed: " + inner);
                t.printStackTrace(System.err);
            } catch (Throwable _) {
                // Nada que hacer.
            }
        }
    }

    /** Visibilidad package-private para tests. */
    static boolean isMinaShutdownNoise(Throwable t) {
        if (!(t instanceof IllegalStateException)) return false;
        String msg = t.getMessage();
        if (msg == null || !msg.contains("Executor has been shut down")) return false;
        for (StackTraceElement el : t.getStackTrace()) {
            if (el.getClassName().startsWith("org.apache.sshd.")) return true;
        }
        return false;
    }
}
