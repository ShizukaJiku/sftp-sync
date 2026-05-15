package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestEntry;
import io.github.shizuka.sftpsync.sftp.LockHeldException;
import io.github.shizuka.sftpsync.sftp.LockInfo;
import io.github.shizuka.sftpsync.sftp.RemoteLockManager;
import io.github.shizuka.sftpsync.sftp.RemoteManifestStore;
import io.github.shizuka.sftpsync.sftp.SftpSession;
import io.github.shizuka.sftpsync.sftp.SftpSession.HostKeyMode;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * Detecta y opcionalmente repara inconsistencias entre el manifest remoto y
 * el filesystem del server.
 *
 * <p>El manifest server-side es la fuente de verdad de qué archivos "deberían"
 * estar sincronizados. Si un archivo está en el manifest pero NO existe
 * físicamente en el server (entry fantasma), los {@code pull} fallan con
 * {@code No such file}. Esto ocurre cuando se borran archivos por fuera de
 * sftp-sync (WinSCP, docker exec, scripts manuales, etc.) — la eliminación
 * no le reporta al manifest.
 *
 * <p>El comando hace dos cosas:
 * <ol>
 *   <li><b>Detect</b>: itera el manifest y hace {@code stat} de cada entry
 *       contra el server. Reporta cuáles son fantasmas.</li>
 *   <li><b>Fix</b> (opcional con {@code --auto}): adquiere el lock remoto,
 *       reescribe el manifest sin las entries fantasma, y guarda atómicamente.
 *       Tras esto, los {@code pull} completan limpio.</li>
 * </ol>
 *
 * <p>Sin {@code --auto} es <b>dry run</b>: solo reporta, no toca el server.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — sin fantasmas, o reparación exitosa.</li>
 *   <li>{@code 1/2/3/5} — config / auth / SSH / I/O (como otros comandos).</li>
 *   <li>{@code 6} — lock tomado por otro cliente.</li>
 *   <li>{@code 7} — hay fantasmas (dry run, no se modificó nada).</li>
 * </ul>
 */
@Command(
    name = "repair",
    description = "Detectar (y opcionalmente reparar) entries fantasma del manifest remoto."
)
public final class RepairCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--insecure", description = "Aceptar cualquier host key.")
    boolean insecure;

    @Option(names = "--auto",
            description = "Aplicar el fix: eliminar las entries fantasma del manifest. "
                + "Default off (solo reporta). Requiere lock remoto.")
    boolean auto;

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
            Manifest remote = RemoteManifestStore.loadOrEmpty(
                session.sftp(), remoteRoot, config.clientId());
            if (remote.entries().isEmpty()
                && !RemoteManifestStore.exists(session.sftp(), remoteRoot)) {
                out.println("No hay manifest remoto en " + remoteRoot + " — nada que reparar.");
                return 0;
            }

            out.println("Manifest remoto: " + remote.entries().size() + " entries.");
            out.println("Verificando existencia de cada archivo en el server...");

            List<String> phantoms = findPhantoms(session.sftp(), remoteRoot, remote);

            if (phantoms.isEmpty()) {
                out.println();
                out.println("Manifest consistente con el filesystem del server: 0 entries fantasma.");
                return 0;
            }

            out.println();
            out.println("Entries fantasma detectadas: " + phantoms.size()
                + " (existen en manifest pero NO en el filesystem):");
            for (String p : phantoms) {
                out.println("  fantasma  " + p);
            }

            if (!auto) {
                out.println();
                out.println("Dry run (sin --auto): el manifest NO se modificó.");
                out.println("Para aplicar el fix: sftp-sync repair --auto"
                    + (insecure ? " --insecure" : ""));
                return 7;
            }

            // --- Fix: lock + rewrite manifest ---
            return applyFix(session, config, remoteRoot, phantoms, out, err);
        } catch (IOException e) {
            return SftpErrors.mapToExitCode(e, err);
        }
    }

    /** Itera el manifest verificando con {@code stat} qué entries existen en el server. */
    private static List<String> findPhantoms(SftpClient sftp, String remoteRoot, Manifest remote)
            throws IOException {
        List<String> phantoms = new ArrayList<>();
        int checked = 0;
        for (String relPath : remote.entries().keySet()) {
            String fullPath = RemoteManifestStore.joinRemote(remoteRoot, relPath);
            try {
                sftp.stat(fullPath);
            } catch (SftpException e) {
                if (e.getStatus() == 2) {
                    phantoms.add(relPath);
                } else {
                    throw e;
                }
            }
            checked++;
            if (checked % 100 == 0) {
                // Progress hint para manifests grandes. Va a stderr para no
                // contaminar stdout (que puede ser parseado por scripts).
                System.err.print(".");
                System.err.flush();
            }
        }
        if (checked >= 100) System.err.println();
        return phantoms;
    }

    /**
     * Adquiere el lock remoto, re-lee el manifest bajo lock (puede haber cambiado),
     * elimina las entries fantasma, y guarda atómicamente.
     */
    private Integer applyFix(SftpSession session, SyncConfig config, String remoteRoot,
                              List<String> phantoms, PrintWriter out, PrintWriter err)
            throws IOException {
        out.println();
        out.println("Aplicando fix: eliminando " + phantoms.size()
            + " entries del manifest remoto...");

        String holder = RemoteLockManager.makeHolder(config.clientId());
        LockInfo lock;
        try {
            lock = RemoteLockManager.acquireOrSteal(session.sftp(), remoteRoot,
                holder, "repair", LockInfo.DEFAULT_TTL_SECONDS);
        } catch (LockHeldException e) {
            err.println("Lock ya tomado por " + e.holder().holder()
                + " (" + e.holder().operation() + "). Reintentá más tarde.");
            return 6;
        }

        try {
            // Re-leer bajo lock — otro cliente puede haber modificado el manifest
            // entre nuestra detección inicial y este punto.
            Manifest underLock = RemoteManifestStore.loadOrEmpty(
                session.sftp(), remoteRoot, config.clientId());

            // Re-verificar fantasmas para el manifest re-leído. Solo eliminamos
            // los que SIGUEN siendo fantasmas — descartamos los que el otro
            // cliente ya removió/repobló.
            List<String> stillPhantom = new ArrayList<>();
            for (String relPath : phantoms) {
                if (!underLock.entries().containsKey(relPath)) continue;
                String fullPath = RemoteManifestStore.joinRemote(remoteRoot, relPath);
                try {
                    session.sftp().stat(fullPath);
                    // Existe! El otro cliente repobló. Saltearlo.
                } catch (SftpException e) {
                    if (e.getStatus() == 2) stillPhantom.add(relPath);
                    else throw e;
                }
            }

            if (stillPhantom.isEmpty()) {
                out.println("Tras releer el manifest bajo lock, las entries ya no son fantasma.");
                return 0;
            }

            Map<String, ManifestEntry> repaired = new TreeMap<>(underLock.entries());
            for (String p : stillPhantom) repaired.remove(p);
            Manifest newManifest = Manifest.of(config.clientId(), repaired);
            RemoteManifestStore.save(session.sftp(), remoteRoot, newManifest);

            out.println("Manifest actualizado: " + underLock.entries().size()
                + " -> " + repaired.size() + " entries (" + stillPhantom.size() + " removidas).");
            return 0;
        } finally {
            try {
                RemoteLockManager.release(session.sftp(), remoteRoot);
            } catch (IOException ignored) {
                // Best-effort. El lock tiene TTL, eventualmente expira.
            }
        }
    }
}
