package io.github.shizuka.sftpsync.manifest;

import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.util.Hashing;
import io.github.shizuka.sftpsync.util.IgnoreMatcher;
import io.github.shizuka.sftpsync.util.PathValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * Escanea una carpeta local y construye un {@link Manifest}.
 *
 * <p>Para cada archivo regular (no directorios, no symlinks):
 * <ol>
 *   <li>Si el path matchea algún patrón de {@code ignore} → skip.</li>
 *   <li>Si {@code size > maxFileSize} → skip + warn.</li>
 *   <li>Si el cache tiene una entry con {@code mtime+size} idénticos → reuse hash.</li>
 *   <li>Si no → calcular SHA-256 y actualizar el cache.</li>
 * </ol>
 *
 * <p>Directorios completos ignorados (ej. {@code target/}, {@code .git/},
 * {@code node_modules/}) se podan con {@code SKIP_SUBTREE} para no descender —
 * el costo de un scan grande baja varios órdenes de magnitud en repos típicos.
 *
 * <p>Al final: purga del cache las entries de archivos que ya no existen, así
 * el cache no crece indefinidamente.
 *
 * <p>El {@link ScanCache} pasado se mutará durante el scan. El caller decide
 * si lo persiste con {@link ScanCacheStore#save}.
 */
public final class ManifestBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ManifestBuilder.class);

    private final Path root;
    private final IgnoreMatcher ignore;
    private final ScanCache cache;
    private final long maxFileSizeBytes;
    private final String clientId;

    public ManifestBuilder(Path root, SyncConfig config, ScanCache cache) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.ignore = IgnoreMatcher.fromConfig(this.root, config);
        this.cache = cache;
        this.maxFileSizeBytes = (long) config.maxFileSizeMB() * 1024L * 1024L;
        this.clientId = config.clientId();
    }

    /** Scan ahora. Devuelve un manifest sorted con todas las entries vivas. */
    public Manifest build() throws IOException {
        TreeMap<String, ManifestEntry> entries = new TreeMap<>();
        Set<String> seenPaths = new HashSet<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String relDir = toRelative(dir);
                // Probamos con trailing slash para que matchee patrones dir-only
                // como "target/", y sin para patrones que sean nombres sueltos
                // como ".git".
                if (ignore.matches(relDir + "/") || ignore.matches(relDir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String relativePath = toRelative(file);
                if (ignore.matches(relativePath)) return FileVisitResult.CONTINUE;

                String winIssue = PathValidation.findWindowsIssue(relativePath);
                if (winIssue != null) {
                    LOG.warn("Path no compatible con Windows, skip: {} ({})",
                        relativePath, winIssue);
                    return FileVisitResult.CONTINUE;
                }

                long size = attrs.size();
                if (size > maxFileSizeBytes) {
                    LOG.warn("Skipping oversize file ({} bytes > {} max): {}",
                        size, maxFileSizeBytes, relativePath);
                    return FileVisitResult.CONTINUE;
                }
                long mtime = attrs.lastModifiedTime().toMillis();
                String hash = cache.lookup(relativePath, mtime, size).orElse(null);
                if (hash == null) {
                    hash = Hashing.sha256(file);
                    cache.put(relativePath, mtime, size, hash);
                }
                entries.put(relativePath, new ManifestEntry(hash, size));
                seenPaths.add(relativePath);
                return FileVisitResult.CONTINUE;
            }
        });

        cache.retainOnly(seenPaths);
        return Manifest.of(clientId, entries);
    }

    /** Path relativo a la raíz, normalizado a forward-slash (cross-OS). */
    private String toRelative(Path absolute) {
        return root.relativize(absolute).toString().replace('\\', '/');
    }
}
