package io.github.shizuka.sftpsync.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Inicializa una carpeta local enganchándola a un remoto SFTP.
 *
 * <p>Crea {@code .sync/config.json} con los datos de conexión, genera un {@code clientId}
 * único, y dispara el primer sync (push completo si el remoto está vacío, pull completo
 * si el remoto ya fue inicializado por otro cliente).
 */
@Command(
    name = "init",
    description = "Inicializar la carpeta actual conectándola a un remoto SFTP."
)
public final class InitCommand implements Callable<Integer> {

    @Option(names = "--host",        description = "Host del servidor SFTP.")
    String host;

    @Option(names = "--port",        description = "Puerto SFTP. Default: 22.", defaultValue = "22")
    int port;

    @Option(names = "--user",        description = "Usuario SFTP.")
    String user;

    @Option(names = "--key",         description = "Path a la clave privada SSH (default: ~/.ssh/id_ed25519).")
    String keyPath;

    @Option(names = "--remote-root", description = "Path remoto donde sincronizar (ej. /upload/proyecto).")
    String remoteRoot;

    @Option(names = "--non-interactive", description = "No prompts. Falla si faltan opciones.", defaultValue = "false")
    boolean nonInteractive;

    @Override
    public Integer call() {
        // TODO: implementar paso 2 del plan.
        System.out.println("[init] not yet implemented");
        return 0;
    }
}
