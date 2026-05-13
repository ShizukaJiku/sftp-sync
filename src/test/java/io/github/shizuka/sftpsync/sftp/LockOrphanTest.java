package io.github.shizuka.sftpsync.sftp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de detección de locks huérfanos. Lógica pura — no requiere servidor.
 */
class LockOrphanTest {

    @Test
    @DisplayName("isOrphan returns false for a fresh heartbeat")
    void freshHeartbeat_notOrphan() {
        Instant now = Instant.parse("2026-05-13T10:00:00Z");
        LockInfo lock = new LockInfo("h", "push",
            "2026-05-13T09:58:00Z", "2026-05-13T09:59:50Z", 300);
        assertThat(RemoteLockManager.isOrphan(lock, now)).isFalse();
    }

    @Test
    @DisplayName("isOrphan returns true when lastHeartbeatAt is older than ttl")
    void staleHeartbeat_orphan() {
        Instant now = Instant.parse("2026-05-13T10:06:00Z");
        LockInfo lock = new LockInfo("h", "push",
            "2026-05-13T09:00:00Z", "2026-05-13T10:00:00Z", 300); // 6 min < 5 min ttl
        assertThat(RemoteLockManager.isOrphan(lock, now)).isTrue();
    }

    @Test
    @DisplayName("isOrphan returns false at exactly ttl seconds")
    void exactlyAtTtl_notOrphan() {
        Instant now = Instant.parse("2026-05-13T10:05:00Z");
        LockInfo lock = new LockInfo("h", "push",
            "2026-05-13T09:00:00Z", "2026-05-13T10:00:00Z", 300);
        assertThat(RemoteLockManager.isOrphan(lock, now)).isFalse();
    }

    @Test
    @DisplayName("isOrphan returns true for corrupt lastHeartbeatAt timestamp")
    void corruptTimestamp_treatedAsOrphan() {
        Instant now = Instant.now();
        LockInfo lock = new LockInfo("h", "push",
            "2026-05-13T10:00:00Z", "not-a-timestamp", 300);
        assertThat(RemoteLockManager.isOrphan(lock, now)).isTrue();
    }

    @Test
    @DisplayName("lockNewPath points to .sync/lock.new")
    void lockNewPath_isCorrect() {
        assertThat(RemoteLockManager.lockNewPath("/sftp"))
            .isEqualTo("/sftp/.sync/lock.new");
    }
}
