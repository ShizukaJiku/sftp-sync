package io.github.shizuka.sftpsync.diff;

import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestEntry;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Calcula el {@link ChangeSet} a partir de los tres snapshots {@code (base, local, remote)}.
 *
 * <p>La tabla de verdad completa está en {@code docs/design.md} §7. Implementada
 * acá literalmente: para cada path que aparece en al menos uno de los tres manifests,
 * extraemos los hashes y aplicamos la decisión basada en si cambió respecto de base
 * y si local/remote coinciden entre sí.
 *
 * <p>La identidad de un archivo es {@code sha256} — {@code size} se chequea pero no
 * se usa para decidir (un cambio de tamaño con mismo hash es imposible salvo colisión,
 * que ignoramos).
 *
 * <p>Stateless.
 */
public final class ThreeWayDiffer {

    private ThreeWayDiffer() {}

    public static ChangeSet diff(Manifest base, Manifest local, Manifest remote) {
        Set<String> toUpload = new TreeSet<>();
        Set<String> toDownload = new TreeSet<>();
        Set<String> toDeleteLocal = new TreeSet<>();
        Set<String> toDeleteRemote = new TreeSet<>();
        Set<String> conflicts = new TreeSet<>();
        Set<String> unchanged = new TreeSet<>();

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(base.entries().keySet());
        allPaths.addAll(local.entries().keySet());
        allPaths.addAll(remote.entries().keySet());

        for (String path : allPaths) {
            String b = hashOrNull(base, path);
            String l = hashOrNull(local, path);
            String r = hashOrNull(remote, path);

            classify(path, b, l, r,
                toUpload, toDownload, toDeleteLocal, toDeleteRemote,
                conflicts, unchanged);
        }

        return new ChangeSet(
            toUpload, toDownload, toDeleteLocal, toDeleteRemote, conflicts, unchanged);
    }

    private static String hashOrNull(Manifest m, String path) {
        ManifestEntry e = m.entries().get(path);
        return e == null ? null : e.sha256();
    }

    /**
     * Aplica la tabla de verdad de §7. Cada combinación {@code (b, l, r)} cae en
     * exactamente una categoría.
     */
    private static void classify(
            String path, String b, String l, String r,
            Set<String> toUpload, Set<String> toDownload,
            Set<String> toDeleteLocal, Set<String> toDeleteRemote,
            Set<String> conflicts, Set<String> unchanged) {

        // ---------------- Caso: base existe ----------------
        if (b != null) {
            // Borrado en ambos lados: skip.
            if (l == null && r == null) {
                return;
            }
            // Borrado solo local: si remote == base, push borra; si difiere, conflicto.
            if (l == null) {
                if (r.equals(b)) {
                    toDeleteRemote.add(path);
                } else {
                    conflicts.add(path);
                }
                return;
            }
            // Borrado solo remote: si local == base, pull borra; si difiere, conflicto.
            if (r == null) {
                if (l.equals(b)) {
                    toDeleteLocal.add(path);
                } else {
                    conflicts.add(path);
                }
                return;
            }
            // Los tres existen.
            if (l.equals(r)) {
                // Local y remote coinciden — ya estamos sincronizados (con o sin cambios desde base).
                unchanged.add(path);
                return;
            }
            // Local y remote difieren.
            if (l.equals(b)) {
                // Solo el remote cambió: pull baja.
                toDownload.add(path);
                return;
            }
            if (r.equals(b)) {
                // Solo el local cambió: push sube.
                toUpload.add(path);
                return;
            }
            // Ambos cambiaron a hashes distintos: conflicto.
            conflicts.add(path);
            return;
        }

        // ---------------- Caso: base no existe ----------------
        // (archivo nuevo desde la última sync)
        if (l == null && r == null) {
            return; // imposible si está en allPaths, pero defensivo.
        }
        if (l == null) {
            // Solo remote tiene: pull baja.
            toDownload.add(path);
            return;
        }
        if (r == null) {
            // Solo local tiene: push sube.
            toUpload.add(path);
            return;
        }
        // Ambos nuevos.
        if (l.equals(r)) {
            // Ambos crearon el mismo contenido: ya sincronizado (raro pero válido).
            unchanged.add(path);
            return;
        }
        // Ambos crearon distintos: conflicto.
        conflicts.add(path);
    }
}
