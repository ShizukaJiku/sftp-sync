package io.github.shizuka.sftpsync.manifest;

/**
 * Entrada del scancache local. Cachea {@code mtime} y {@code size} junto con el
 * hash calculado, así un re-scan puede saltearse el rehash de archivos cuyas
 * estadísticas no cambiaron desde el último scan.
 *
 * <p>El cache es local-only — nunca viaja al remoto.
 */
public record ScanCacheEntry(long mtime, long size, String sha256) {

    public ScanCacheEntry {
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("sha256 no puede ser nulo/vacío");
        }
        if (mtime < 0) {
            throw new IllegalArgumentException("mtime no puede ser negativo: " + mtime);
        }
        if (size < 0) {
            throw new IllegalArgumentException("size no puede ser negativo: " + size);
        }
    }
}
