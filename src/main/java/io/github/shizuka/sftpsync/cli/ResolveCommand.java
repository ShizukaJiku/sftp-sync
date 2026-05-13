package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.manifest.BaseStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestEntry;
import io.github.shizuka.sftpsync.util.Hashing;
import io.github.shizuka.sftpsync.util.Hostname;
import java.nio.file.StandardCopyOption;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Resuelve un conflicto detectado por {@code pull} eligiendo qué versión queda.
 *
 * <p>Tras un pull con conflicto existen {@code <path>} (versión local) y
 * {@code <path>.remote} (versión remota). Este comando aplica una de tres
 * estrategias y actualiza {@code .sync/base.json} para "cerrar" el conflicto:
 *
 * <ul>
 *   <li>{@code --keep-local}: borra {@code <path>.remote}, mantiene {@code <path>}
 *       como está. Anota el hash local en {@code base} para que el próximo push
 *       lo suba (visto como un cambio local desde la base).</li>
 *   <li>{@code --keep-remote}: sobrescribe {@code <path>} con {@code <path>.remote}.
 *       Anota el hash remoto en {@code base} (ya está sincronizado, no requiere
 *       push).</li>
 *   <li>{@code --keep-both}: renombra {@code <path>} → {@code <path>.local-<host>}
 *       y mueve {@code <path>.remote} → {@code <path>}. Ambos quedan como archivos
 *       nuevos para el próximo push.</li>
 * </ul>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — conflicto resuelto.</li>
 *   <li>{@code 1} — sin config / sin base.json.</li>
 *   <li>{@code 4} — el archivo conflictivo no existe (no hay {@code .remote}).</li>
 *   <li>{@code 5} — error de I/O.</li>
 * </ul>
 */
@Command(
    name = "resolve",
    description = "Resolver un conflicto post-pull eligiendo qué versión queda."
)
public final class ResolveCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

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

        Path local = root.resolve(path);
        Path remote = root.resolve(path + ".remote");

        if (!Files.exists(remote)) {
            err.println("No hay archivo .remote para " + path + ".");
            err.println("Esperaba: " + remote);
            err.println("¿Estás seguro que hay un conflicto activo? Corré 'sftp-sync status'.");
            return 4;
        }

        try {
            Manifest base = BaseStore.loadOrEmpty(root);
            Map<String, ManifestEntry> entries = new LinkedHashMap<>(base.entries());

            // El hash remoto antes de resolver — anclamos base a este valor en las tres
            // estrategias para que el próximo diff vea el resultado esperado:
            // - keep-local  → (B=R, L=local, R=R) ⇒ toUpload
            // - keep-remote → (B=R, L=R, R=R)     ⇒ unchanged
            // - keep-both   → (B=R, L=R, R=R)     ⇒ unchanged para `path`, .local-host nuevo ⇒ toUpload
            long remoteSize = Files.size(remote);
            String remoteSha = Hashing.sha256(remote);
            entries.put(path, new ManifestEntry(remoteSha, remoteSize));

            if (strategy.keepLocal) {
                Files.deleteIfExists(remote);
                if (Files.exists(local)) {
                    long size = Files.size(local);
                    String sha = Hashing.sha256(local);
                    out.println("resolved (keep-local): " + path
                        + " — el próximo push subirá tu versión (" + sha.substring(0, 12) + "..., "
                        + size + " bytes).");
                } else {
                    out.println("resolved (keep-local): " + path
                        + " — el próximo push borrará el archivo en el remoto.");
                }
            } else if (strategy.keepRemote) {
                Files.createDirectories(local.getParent() != null ? local.getParent() : root);
                Files.move(remote, local, StandardCopyOption.REPLACE_EXISTING);
                out.println("resolved (keep-remote): " + path
                    + " — local sobrescrito con la versión remota.");
            } else if (strategy.keepBoth) {
                Path localRenamed = local.resolveSibling(
                    local.getFileName() + ".local-" + Hostname.get());
                if (Files.exists(local)) {
                    Files.move(local, localRenamed, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(remote, local, StandardCopyOption.REPLACE_EXISTING);
                out.println("resolved (keep-both): " + path
                    + " conserva la versión remota, tu versión movida a "
                    + root.relativize(localRenamed) + ".");
                out.println(root.relativize(localRenamed) + " se subirá en el próximo push.");
            } else {
                err.println("Tenés que pasar una estrategia: --keep-local | --keep-remote | --keep-both.");
                return 1;
            }

            BaseStore.save(root, Manifest.of(config.clientId(), entries));
            return 0;

        } catch (IOException e) {
            err.println("Error resolviendo conflicto: " + e.getMessage());
            return 5;
        }
    }

}
