package io.github.shizuka.sftpsync.cli;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestBuilder;
import io.github.shizuka.sftpsync.manifest.ManifestStore;
import io.github.shizuka.sftpsync.manifest.ScanCache;
import io.github.shizuka.sftpsync.manifest.ScanCacheStore;
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
 * Comando debug-only: escanea la carpeta local y muestra el manifest.
 *
 * <p>Útil para verificar end-to-end que el pipeline (config → ignore → walk →
 * hash → cache → manifest) está funcionando antes de tener push/pull. Hidden
 * en el help para no confundir a usuarios finales.
 */
@Command(
    name = "scan",
    description = "Escanear la carpeta local e imprimir el manifest. (debug)",
    hidden = true
)
public final class ScanCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Spec
    CommandSpec spec;

    @Option(names = "--no-cache",
            description = "Forzar rehash, ignorar el scancache existente.")
    boolean noCache;

    @Option(names = "--save",
            description = "Persistir el manifest en .sync/manifest.json.")
    boolean save;

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

        ScanCache cache = noCache ? new ScanCache() : ScanCacheStore.loadOrEmpty(root);

        Manifest manifest;
        try {
            manifest = new ManifestBuilder(root, config, cache).build();
        } catch (IOException e) {
            err.println("Error escaneando: " + e.getMessage());
            return 2;
        }

        // Persistir cache (best effort: si falla, log pero no bloquea).
        try {
            ScanCacheStore.save(root, cache);
        } catch (IOException e) {
            err.println("Warning: no pude actualizar scancache: " + e.getMessage());
        }

        if (save) {
            try {
                ManifestStore.save(root, manifest);
                err.println("[scan] manifest guardado en " + ManifestStore.path(root));
            } catch (IOException e) {
                err.println("Error guardando manifest: " + e.getMessage());
                return 3;
            }
        }

        try {
            String pretty = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT)
                                    .asString(manifest);
            out.println(pretty);
        } catch (IOException e) {
            err.println("Error serializando manifest: " + e.getMessage());
            return 4;
        }

        return 0;
    }
}
