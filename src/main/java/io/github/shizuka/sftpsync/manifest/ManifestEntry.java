package io.github.shizuka.sftpsync.manifest;

/**
 * Una entrada en el manifest: identidad de contenido + tamaño.
 *
 * <p>La identidad es {@code (sha256, size)}. {@code mtime} NO forma parte porque
 * varía entre filesystems (FAT32 vs NTFS vs ext4) y rompería el three-way diff
 * cross-OS. {@code size} se incluye solo para optimizar el cache local — la
 * decisión de "es el mismo archivo" sigue siendo por hash.
 */
public record ManifestEntry(String sha256, long size) {

    public ManifestEntry {
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("sha256 no puede ser nulo/vacío");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size no puede ser negativo: " + size);
        }
    }
}
