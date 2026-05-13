package io.github.shizuka.sftpsync.sftp;

import java.io.IOException;

/**
 * Lanzada cuando {@link RemoteLockManager#acquire} encuentra el lock ya tomado.
 *
 * <p>El {@link #holder()} expone el {@link LockInfo} del cliente que tiene el lock,
 * útil para mensajes de diagnóstico ("otro cliente X está haciendo push desde Y").
 *
 * <p>Hereda de {@link IOException} para que se propague natural por la API de SFTP
 * (que es checked-IOException everywhere) sin requerir handling especial en los
 * callers que solo quieren reportar el error y abortar.
 */
public class LockHeldException extends IOException {

    private final LockInfo holder;

    public LockHeldException(LockInfo holder, Throwable cause) {
        super("Lock ya tomado por " + holder.holder()
              + " (operation=" + holder.operation()
              + ", acquiredAt=" + holder.acquiredAt() + ")",
              cause);
        this.holder = holder;
    }

    /** Info del cliente que mantiene el lock. */
    public LockInfo holder() {
        return holder;
    }
}
