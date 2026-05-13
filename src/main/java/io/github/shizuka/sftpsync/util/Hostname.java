package io.github.shizuka.sftpsync.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resuelve el hostname local con fallback a {@code "unknown-host"}.
 *
 * <p>Se usa para construir identificadores humano-legibles ({@code holder} del
 * lock, sufijo de archivos {@code .local-<host>} en resolve, etc.). Cero throws
 * para no propagar errores triviales de DNS local a operaciones de I/O.
 */
public final class Hostname {

    private Hostname() {}

    public static String get() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
