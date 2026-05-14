package io.github.shizuka.sftpsync.sftp;

import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class LockHeartbeat implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LockHeartbeat.class);

    private final SftpClient sftp;
    private final String remoteRoot;
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> task;
    private LockInfo currentLock;

    private LockHeartbeat(SftpClient sftp, String remoteRoot, LockInfo initial,
                          ScheduledExecutorService scheduler, ScheduledFuture<?> task) {
        this.sftp = sftp;
        this.remoteRoot = remoteRoot;
        this.currentLock = initial;
        this.scheduler = scheduler;
        this.task = task;
    }

    public static LockHeartbeat start(SftpClient sftp, String remoteRoot, LockInfo initial) {
        int periodSec = Math.max(1, initial.ttlSeconds() / 3);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-heartbeat");
            t.setDaemon(true);
            return t;
        });
        try {
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
