package io.github.shizuka.sftpsync.sftp;

import net.schmizz.sshj.sftp.SFTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hilo de fondo que refresca el {@code lastHeartbeatAt} del lock remoto cada
 * {@code ttlSeconds / 3} segundos. Mientras este heartbeat esté vivo, otros
 * clientes que vean el lock NO lo considerarán huérfano.
 *
 * <p>Uso:
 * <pre>{@code
 * LockInfo lock = RemoteLockManager.acquire(sftp, root, holder, "push", 300);
 * try (LockHeartbeat hb = LockHeartbeat.start(sftp, root, lock)) {
 *     // ...trabajo largo...
 * }
 * RemoteLockManager.release(sftp, root);
 * }</pre>
 *
 * <p><b>Importante:</b> el {@code SFTPClient} que recibe acá debe estar dedicado
 * al heartbeat. NO compartirlo con otro hilo que esté haciendo uploads/downloads
 * largos — sshj no es threadsafe para operaciones OPEN/WRITE concurrentes sobre
 * la misma sesión.
 */
public final class LockHeartbeat implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LockHeartbeat.class);

    private final SFTPClient sftp;
    private final String remoteRoot;
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> task;
    private LockInfo currentLock;

    private LockHeartbeat(SFTPClient sftp, String remoteRoot, LockInfo initial,
                          ScheduledExecutorService scheduler, ScheduledFuture<?> task) {
        this.sftp = sftp;
        this.remoteRoot = remoteRoot;
        this.currentLock = initial;
        this.scheduler = scheduler;
        this.task = task;
    }

    /**
     * Arranca un heartbeat para el lock dado. Intervalo = {@code ttlSeconds / 3},
     * mínimo 1 s para no encarar TTLs absurdos en tests.
     */
    public static LockHeartbeat start(SFTPClient sftp, String remoteRoot, LockInfo initial) {
        int periodSec = Math.max(1, initial.ttlSeconds() / 3);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-heartbeat");
            t.setDaemon(true);
            return t;
        });
        try {
            // Holder construido vía referencia capturada para que el task tenga acceso.
            final LockHeartbeat[] ref = new LockHeartbeat[1];
            ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> { if (ref[0] != null) ref[0].tick(); },
                periodSec, periodSec, TimeUnit.SECONDS);
            ref[0] = new LockHeartbeat(sftp, remoteRoot, initial, scheduler, task);
            return ref[0];
        } catch (RuntimeException e) {
            scheduler.shutdownNow();
            throw e;
        }
    }

    @Override
    public void close() {
        task.cancel(false);
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Escribe el lock con un {@code lastHeartbeatAt} fresco. Sobrescribe atómicamente. */
    private void tick() {
        try {
            LockInfo refreshed = new LockInfo(
                currentLock.holder(), currentLock.operation(),
                currentLock.acquiredAt(),
                Instant.now().toString(),
                currentLock.ttlSeconds());
            RemoteLockManager.writeOverwrite(sftp, remoteRoot, refreshed);
            currentLock = refreshed;
        } catch (IOException e) {
            LOG.warn("Heartbeat tick falló: {}. El lock puede ser considerado huérfano.",
                e.getMessage());
        }
    }
}
