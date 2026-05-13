package io.github.shizuka.sftpsync.diff;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resultado de un three-way diff entre {@code base}, {@code local} y {@code remote}.
 *
 * <p>Cada set agrupa paths según la acción requerida desde la perspectiva del cliente.
 * Los nombres reflejan "lo que el cliente debe hacer" durante un push o pull:
 *
 * <ul>
 *   <li><b>toUpload</b>: paths que existen local con un hash distinto al remoto y que
 *       reflejan un cambio nuestro desde base — push los sube.</li>
 *   <li><b>toDownload</b>: paths que existen remoto con un hash distinto al local y que
 *       reflejan un cambio remoto desde base — pull los baja.</li>
 *   <li><b>toDeleteLocal</b>: paths borrados en el remoto desde base, presentes local
 *       sin cambios — pull los borra de disco.</li>
 *   <li><b>toDeleteRemote</b>: paths borrados local desde base, presentes remoto sin
 *       cambios — push los borra del remoto.</li>
 *   <li><b>conflicts</b>: paths donde local y remoto divergieron de manera incompatible.
 *       Push aborta; pull baja el remoto como {@code <path>.remote} y deja el local
 *       intacto.</li>
 *   <li><b>unchanged</b>: paths que están idénticos en local y remoto (haya o no
 *       cambiado base) — no requieren acción.</li>
 * </ul>
 *
 * <p>Reglas completas en {@code docs/design.md} §7. Todos los sets están ordenados
 * alfabéticamente (TreeSet) para que los reportes sean estables y diff-friendly.
 */
public record ChangeSet(
    Set<String> toUpload,
    Set<String> toDownload,
    Set<String> toDeleteLocal,
    Set<String> toDeleteRemote,
    Set<String> conflicts,
    Set<String> unchanged
) {

    public ChangeSet {
        toUpload       = freeze(toUpload);
        toDownload     = freeze(toDownload);
        toDeleteLocal  = freeze(toDeleteLocal);
        toDeleteRemote = freeze(toDeleteRemote);
        conflicts      = freeze(conflicts);
        unchanged      = freeze(unchanged);
    }

    private static Set<String> freeze(Set<String> in) {
        if (in == null || in.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(new TreeSet<>(in));
    }

    /** {@code true} si no hay ningún cambio en ningún lado. */
    public boolean isClean() {
        return toUpload.isEmpty() && toDownload.isEmpty()
            && toDeleteLocal.isEmpty() && toDeleteRemote.isEmpty()
            && conflicts.isEmpty();
    }

    /** {@code true} si hay conflictos a resolver. */
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    /** Total de operaciones pendientes (sin contar conflicts ni unchanged). */
    public int pendingOperations() {
        return toUpload.size() + toDownload.size()
            + toDeleteLocal.size() + toDeleteRemote.size();
    }
}
