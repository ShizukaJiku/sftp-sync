package io.github.shizuka.sftpsync.util;

/**
 * Expansión de paths con prefijos especiales (por ahora, solo {@code ~/}).
 */
public final class PathExpansion {

    private PathExpansion() {}

    /**
     * Expande {@code ~} y {@code ~/...} al home del usuario actual.
     *
     * <p>Ejemplos:
     * <ul>
     *   <li>{@code "~"} → {@code "/home/user"}</li>
     *   <li>{@code "~/.ssh/id_ed25519"} → {@code "/home/user/.ssh/id_ed25519"}</li>
     *   <li>{@code "/absolute"} → {@code "/absolute"} (sin cambios)</li>
     *   <li>{@code null} → {@code null}</li>
     * </ul>
     *
     * <p>NO expande {@code ~user/} (forma de referirse al home de otro usuario).
     * Esa forma es rara y agregarla es trivial cuando haga falta.
     */
    public static String expandTilde(String path) {
        if (path != null && (path.equals("~") || path.startsWith("~/"))) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
