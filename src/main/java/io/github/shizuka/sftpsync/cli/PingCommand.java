package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.sftp.SftpSession;
import io.github.shizuka.sftpsync.sftp.SftpSession.HostKeyMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Comando debug: valida la conexión al servidor SFTP definido en la config.
 *
 * <p>No transfiere datos. Solo conecta, hace login, abre el subsistema SFTP,
 * verifica que la carpeta remota existe, y se desconecta limpio.
 *
 * <p>Útil para diagnosticar problemas de configuración antes de tener push/pull.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — todo bien.</li>
 *   <li>{@code 1} — no hay config o no se puede leer.</li>
 *   <li>{@code 2} — autenticación falló (usuario, clave, passphrase).</li>
 *   <li>{@code 3} — error de transporte SSH (host key, protocolo).</li>
 *   <li>{@code 4} — la carpeta remota no existe.</li>
 *   <li>{@code 5} — error genérico de I/O (DNS, connect refused, timeout).</li>
 * </ul>
 */
@Command(
    name = "ping",
    description = "Validar la conexión al servidor SFTP. (debug)",
    hidden = true
)
public final class PingCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure",
            description = "Aceptar cualquier host key (skip known_hosts). Solo para primer setup.")
    boolean insecure;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        Path root = Path.of(parent.directory).toAbsolutePath().normalize();

        SyncConfig config;
        try {
            config = SyncConfigStore.load(root);
        } catch (NoSuchFileException e) {
            err.println("No hay .sync/config.json en " + root);
            err.println("Corré 'sftp-sync init' primero.");
            return 1;
        } catch (IOException e) {
            err.println("Error leyendo config: " + e.getMessage());
            return 1;
        }

        HostKeyMode mode = insecure ? HostKeyMode.INSECURE : HostKeyMode.STRICT;
        out.println("Conectando a " + config.remote().user() + "@"
            + config.remote().host() + ":" + config.remote().port() + "...");

        try (SftpSession session = SftpSession.open(config.remote(), mode)) {
            out.println("OK. Server: " + session.serverVersion());

            String remoteRoot = config.remote().remoteRoot();
            org.apache.sshd.sftp.client.SftpClient.Attributes attrs;
            try {
                attrs = session.sftp().stat(remoteRoot);
            } catch (org.apache.sshd.sftp.common.SftpException e) {
                if (e.getStatus() == 2) {
                    err.println("La carpeta remota no existe: " + remoteRoot);
                    err.println("Creála en el server (mkdir -p " + remoteRoot + ")");
                    err.println("o ajustá --remote-root en la config.");
                    return 4;
                }
                throw e;
            }
            if (!attrs.isDirectory()) {
                err.println("El path remoto existe pero no es un directorio: " + remoteRoot);
                return 4;
            }

            int entryCount = 0;
            for (var _ : session.sftp().readDir(remoteRoot)) entryCount++;
            out.println("Carpeta remota OK: " + remoteRoot + " (" + entryCount + " entries)");
            return 0;
        } catch (IOException e) {
            return SftpErrors.mapToExitCode(e, err);
        }
    }
}
