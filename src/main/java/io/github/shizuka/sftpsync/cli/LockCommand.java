package io.github.shizuka.sftpsync.cli;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.sftp.LockHeldException;
import io.github.shizuka.sftpsync.sftp.LockInfo;
import io.github.shizuka.sftpsync.sftp.RemoteLockManager;
import io.github.shizuka.sftpsync.sftp.SftpSession;
import io.github.shizuka.sftpsync.sftp.SftpSession.HostKeyMode;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import picocli.CommandLine.ArgGroup;
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
 * Comando debug: maneja el lock remoto en {@code <remoteRoot>/.sync/lock}.
 *
 * <p>Modos (mutuamente excluyentes):
 * <ul>
 *   <li>Default (sin flags de acción): muestra el lock actual o "no hay lock".</li>
 *   <li>{@code --acquire <op>}: toma el lock con la operación dada (típicamente
 *       "push" o "pull"). Falla si ya hay un lock.</li>
 *   <li>{@code --release}: borra el lock. Idempotente.</li>
 * </ul>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — todo bien.</li>
 *   <li>{@code 1} — no hay config o no se puede leer.</li>
 *   <li>{@code 2} — autenticación falló.</li>
 *   <li>{@code 3} — error de transporte SSH.</li>
 *   <li>{@code 5} — error genérico de I/O.</li>
 *   <li>{@code 6} — el lock ya estaba tomado por otro cliente (solo en --acquire).</li>
 * </ul>
 */
@Command(
    name = "lock",
    description = "Inspeccionar, adquirir o liberar el lock remoto. (debug)",
    hidden = true
)
public final class LockCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure",
            description = "Aceptar cualquier host key (skip known_hosts). Solo para primer setup.")
    boolean insecure;

    /** Acciones mutuamente excluyentes. Si ninguna está presente → modo "read". */
    @ArgGroup(exclusive = true)
    Action action;

    static final class Action {
        @Option(names = "--acquire",
                paramLabel = "<operation>",
                description = "Tomar el lock con la operación dada (ej: push, pull).",
                required = true)
        String acquireOp;

        @Option(names = "--release",
                description = "Liberar el lock (idempotente).",
                required = true)
        boolean release;
    }

    @Option(names = "--ttl",
            description = "TTL del lock en segundos (default: ${DEFAULT-VALUE}).",
            defaultValue = "300")
    int ttlSeconds;

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
            if (action != null && action.acquireOp != null) {
                return doAcquire(session, remoteRoot, config.clientId(),
                                 action.acquireOp, out, err);
            }
            if (action != null && action.release) {
                return doRelease(session, remoteRoot, out);
            }
            return doRead(session, remoteRoot, out);

        } catch (UserAuthException e) {
            err.println("Autenticación falló: " + e.getMessage());
            return 2;
        } catch (TransportException e) {
            err.println("Error SSH: " + e.getMessage());
            return 3;
        } catch (IOException e) {
            err.println("Error de conexión: " + e.getMessage());
            return 5;
        }
    }

    private int doRead(SftpSession session, String remoteRoot, PrintWriter out)
            throws IOException {
        LockInfo lock = RemoteLockManager.read(session.sftp(), remoteRoot);
        if (lock == null) {
            out.println("No hay lock en " + RemoteLockManager.lockPath(remoteRoot));
            return 0;
        }
        String pretty = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT).asString(lock);
        out.println(pretty);
        return 0;
    }

    private int doAcquire(SftpSession session, String remoteRoot, String clientId,
                          String operation, PrintWriter out, PrintWriter err) throws IOException {
        String holder = RemoteLockManager.makeHolder(clientId);
        try {
            LockInfo lock = RemoteLockManager.acquire(
                session.sftp(), remoteRoot, holder, operation, ttlSeconds);
            out.println("OK. Lock tomado en " + RemoteLockManager.lockPath(remoteRoot));
            out.println("  holder:     " + lock.holder());
            out.println("  operation:  " + lock.operation());
            out.println("  acquiredAt: " + lock.acquiredAt());
            out.println("  ttlSeconds: " + lock.ttlSeconds());
            return 0;
        } catch (LockHeldException e) {
            err.println("Lock ya tomado:");
            err.println("  holder:          " + e.holder().holder());
            err.println("  operation:       " + e.holder().operation());
            err.println("  acquiredAt:      " + e.holder().acquiredAt());
            err.println("  lastHeartbeatAt: " + e.holder().lastHeartbeatAt());
            err.println("  ttlSeconds:      " + e.holder().ttlSeconds());
            return 6;
        }
    }

    private int doRelease(SftpSession session, String remoteRoot, PrintWriter out)
            throws IOException {
        boolean removed = RemoteLockManager.release(session.sftp(), remoteRoot);
        out.println(removed
            ? "OK. Lock liberado: " + RemoteLockManager.lockPath(remoteRoot)
            : "No había lock para liberar.");
        return 0;
    }
}
