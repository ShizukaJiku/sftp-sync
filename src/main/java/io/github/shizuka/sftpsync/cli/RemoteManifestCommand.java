package io.github.shizuka.sftpsync.cli;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestStore;
import io.github.shizuka.sftpsync.sftp.RemoteManifestStore;
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
 * Comando debug: lee o escribe el manifest remoto vía SFTP.
 *
 * <p>Modos:
 * <ul>
 *   <li>Default (sin {@code --put}): baja {@code <remoteRoot>/.sync/manifest.json}
 *       y lo imprime. Útil para inspeccionar el estado remoto.</li>
 *   <li>{@code --put}: sube {@code .sync/manifest.json} local al remoto. Requiere
 *       que el manifest local exista — corré {@code sftp-sync scan --save} antes.</li>
 * </ul>
 *
 * <p>No transfiere archivos del proyecto. Solo el JSON del manifest.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — todo bien.</li>
 *   <li>{@code 1} — no hay config o no se puede leer.</li>
 *   <li>{@code 2} — autenticación falló.</li>
 *   <li>{@code 3} — error de transporte SSH.</li>
 *   <li>{@code 4} — el manifest remoto no existe (modo get) o el local no existe (modo put).</li>
 *   <li>{@code 5} — error genérico de I/O.</li>
 * </ul>
 */
@Command(
    name = "remote-manifest",
    description = "Bajar o subir el manifest remoto vía SFTP. (debug)",
    hidden = true
)
public final class RemoteManifestCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure",
            description = "Aceptar cualquier host key (skip known_hosts). Solo para primer setup.")
    boolean insecure;

    @Option(names = "--put",
            description = "Subir el manifest local al remoto (default: bajar).")
    boolean put;

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
        String remoteRoot = config.remote().remoteRoot();

        try (SftpSession session = SftpSession.open(config.remote(), mode)) {
            if (put) {
                return doPut(session, root, remoteRoot, out, err);
            }
            return doGet(session, remoteRoot, out, err);
        } catch (IOException e) {
            return SftpErrors.mapToExitCode(e, err);
        }
    }

    private int doGet(SftpSession session, String remoteRoot,
                      PrintWriter out, PrintWriter err) throws IOException {
        Manifest remote;
        try {
            remote = RemoteManifestStore.load(session.sftp(), remoteRoot);
        } catch (NoSuchFileException e) {
            err.println("No hay manifest remoto en " + e.getMessage());
            err.println("Subí uno con 'sftp-sync remote-manifest --put' (después de 'scan --save').");
            return 4;
        }
        String pretty = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(remote);
        out.println(pretty);
        return 0;
    }

    private int doPut(SftpSession session, Path root, String remoteRoot,
                      PrintWriter out, PrintWriter err) throws IOException {
        Manifest local;
        try {
            local = ManifestStore.load(root);
        } catch (NoSuchFileException e) {
            err.println("No hay manifest local en " + ManifestStore.path(root));
            err.println("Corré 'sftp-sync scan --save' primero.");
            return 4;
        }
        RemoteManifestStore.save(session.sftp(), remoteRoot, local);
        out.println("OK. Manifest subido a "
            + RemoteManifestStore.manifestPath(remoteRoot)
            + " (" + local.entries().size() + " entries).");
        return 0;
    }
}
