package io.github.shizuka.sftpsync.config;

import com.fasterxml.jackson.jr.ob.JSON;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lectura y escritura del archivo {@code .sync/config.json}.
 *
 * <p>El escritor serializa a String y luego escribe el archivo entero de una vez,
 * usando {@code tmp + ATOMIC_MOVE} para garantizar que un crash en mitad del write
 * no deja un archivo corrupto: o queda el viejo, o queda el nuevo.
 *
 * <p>Stateless. Todos los métodos son estáticos.
 */
public final class SyncConfigStore {

    /** Nombre de la carpeta de estado dentro de la raíz del proyecto. */
    public static final String DIR_NAME = ".sync";

    /** Nombre del archivo de configuración. */
    public static final String FILE_NAME = "config.json";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private SyncConfigStore() {}

    /** Path completo al archivo de config para una raíz dada. */
    public static Path configPath(Path projectRoot) {
        return projectRoot.resolve(DIR_NAME).resolve(FILE_NAME);
    }

    /** Path a la carpeta {@code .sync/}. */
    public static Path syncDir(Path projectRoot) {
        return projectRoot.resolve(DIR_NAME);
    }

    /** {@code true} si el archivo de config existe en la raíz dada. */
    public static boolean exists(Path projectRoot) {
        return Files.exists(configPath(projectRoot));
    }

    /**
     * Lee la config desde {@code .sync/config.json}.
     *
     * @throws NoSuchFileException si el archivo no existe.
     * @throws IOException         si hay error de I/O o JSON corrupto.
     */
    public static SyncConfig load(Path projectRoot) throws IOException {
        Path p = configPath(projectRoot);
        if (!Files.exists(p)) {
            throw new NoSuchFileException(p.toString());
        }
        String json = Files.readString(p, StandardCharsets.UTF_8);
        return JSON.std.beanFrom(SyncConfig.class, json);
    }

    /**
     * Escribe la config a {@code .sync/config.json} de forma atómica.
     *
     * <p>Crea {@code .sync/} si no existe. Escribe primero a {@code .tmp} y luego
     * hace rename atómico al archivo final, así un crash a mitad de write no
     * corrompe el config existente.
     */
    public static void save(Path projectRoot, SyncConfig config) throws IOException {
        Path syncDir = syncDir(projectRoot);
        Files.createDirectories(syncDir);
        Path target = configPath(projectRoot);
        Path tmp = syncDir.resolve(FILE_NAME + ".tmp");

        // Serializar a String primero: jackson-jr's write(value, OutputStream)
        // cierra el stream, lo que complica agregar un newline final. Para nuestros
        // tamaños (KB) es trivial y deja la lógica de I/O completamente bajo nuestro
        // control.
        String json = JSON_PRETTY.asString(config) + System.lineSeparator();
        Files.writeString(tmp, json, StandardCharsets.UTF_8);

        try {
            Files.move(tmp, target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Fallback no-atómico para filesystems exóticos sin ATOMIC_MOVE.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
