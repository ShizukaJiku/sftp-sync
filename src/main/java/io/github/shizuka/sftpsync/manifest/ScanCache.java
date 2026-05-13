package io.github.shizuka.sftpsync.manifest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Cache mutable {@code path → (mtime, size, sha256)} usado por el ManifestBuilder
 * para evitar rehashear archivos sin cambios entre scans.
 *
 * <p>Vida del cache: cargado de {@code .sync/scancache.json} al inicio del scan,
 * actualizado durante el scan, persistido al final.
 */
public final class ScanCache {

    private final Map<String, ScanCacheEntry> entries;
    private boolean dirty;

    public ScanCache() {
        this.entries = new HashMap<>();
    }

    public ScanCache(Map<String, ScanCacheEntry> initial) {
        this.entries = new HashMap<>(initial);
    }

    /**
     * Devuelve el hash cacheado SI las stats actuales del archivo coinciden con
     * las cacheadas. Si no coinciden o no hay entry, devuelve empty (caller debe
     * rehashear).
     */
    public Optional<String> lookup(String relativePath, long currentMtime, long currentSize) {
        ScanCacheEntry e = entries.get(relativePath);
        if (e == null) return Optional.empty();
        if (e.mtime() != currentMtime || e.size() != currentSize) return Optional.empty();
        return Optional.of(e.sha256());
    }

    /** Inserta o reemplaza la entry para {@code relativePath}. */
    public void put(String relativePath, long mtime, long size, String sha256) {
        ScanCacheEntry prev = entries.put(relativePath,
            new ScanCacheEntry(mtime, size, sha256));
        if (prev == null || prev.mtime() != mtime || prev.size() != size
            || !prev.sha256().equals(sha256)) {
            dirty = true;
        }
    }

    /**
     * Quita las entries cuyos paths no estén en el set dado.
     *
     * <p>Útil al final del scan para purgar entries de archivos que ya no
     * existen en el filesystem, así el cache no crece indefinidamente.
     */
    public void retainOnly(java.util.Set<String> existingPaths) {
        if (entries.keySet().retainAll(existingPaths)) dirty = true;
    }

    /**
     * {@code true} si hubo mutaciones reales desde el último {@link #clearDirty}.
     * Los callers (especialmente el watcher en hot loop) pueden saltearse la
     * escritura de {@code scancache.json} si no hay cambios.
     */
    public boolean isDirty() {
        return dirty;
    }

    /** Resetea el flag de dirty. Llamar después de persistir. */
    public void clearDirty() {
        dirty = false;
    }

    /** Vista inmutable y ordenada del cache, apta para serialización JSON. */
    public Map<String, ScanCacheEntry> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    public int size() {
        return entries.size();
    }
}
