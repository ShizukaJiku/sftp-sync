package io.github.shizuka.sftpsync.sftp;

import java.time.Instant;

/**
 * Contenido serializado del archivo {@code <remoteRoot>/.sync/lock}.
 *
 * <p>Identifica al cliente que mantiene el lock y la operación que está corriendo.
 * {@code lastHeartbeatAt} se setea igual a
 * {@code acquiredAt}; el hilo de heartbeat lo refresca durante operaciones largas.
 *
 * @param holder           identificador humano-legible del cliente. Formato sugerido:
 *                         {@code <hostname>+pid<pid>+<clientIdShort>}.
 * @param operation        operación que adquirió el lock (típicamente "push" o "pull").
 * @param acquiredAt       ISO-8601 instant del acquire inicial.
 * @param lastHeartbeatAt  ISO-8601 instant del último heartbeat. Igual a
 *                         {@code acquiredAt} si todavía no hay heartbeat thread.
 * @param ttlSeconds       segundos tras el último heartbeat después de los cuales el
 *                         lock se considera huérfano. Tipico: 300.
 */
public record LockInfo(
    String holder,
    String operation,
    String acquiredAt,
    String lastHeartbeatAt,
    int ttlSeconds
) {

    /** TTL default cuando no se especifica. Sigue el ejemplo del design doc. */
    public static final int DEFAULT_TTL_SECONDS = 300;

    public LockInfo {
        if (ttlSeconds == 0) {
            ttlSeconds = DEFAULT_TTL_SECONDS;
        }
    }

    /** Crea un LockInfo nuevo con {@code acquiredAt = lastHeartbeatAt = now}. */
    public static LockInfo now(String holder, String operation, int ttlSeconds) {
        String stamp = Instant.now().toString();
        return new LockInfo(holder, operation, stamp, stamp, ttlSeconds);
    }
}
