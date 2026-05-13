package io.github.shizuka.sftpsync.sftp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitarios de RemoteLockManager sin necesidad de un servidor real.
 *
 * <p>El round-trip real (acquire/read/release y conflicto de doble-acquire)
 * se valida manualmente contra emberstack/sftp con {@code sftp-sync lock}.
 */
class RemoteLockManagerTest {

    @Test
    @DisplayName("lockPath points to .sync/lock under remote root")
    void lockPath_pointsToCorrectFile() {
        assertThat(RemoteLockManager.lockPath("/sftp"))
            .isEqualTo("/sftp/.sync/lock");
        assertThat(RemoteLockManager.lockPath("/upload/project-x"))
            .isEqualTo("/upload/project-x/.sync/lock");
    }

    @Test
    @DisplayName("lockPath tolerates trailing slash in remote root")
    void lockPath_tolerantToTrailingSlash() {
        assertThat(RemoteLockManager.lockPath("/sftp/"))
            .isEqualTo("/sftp/.sync/lock");
    }

    @Test
    @DisplayName("makeHolder produces hostname+pid+clientIdShort format")
    void makeHolder_formatIsConsistent() {
        String holder = RemoteLockManager.makeHolder("9f8a1c2e-aaaa-bbbb-cccc-dddddddddddd");
        assertThat(holder)
            .matches(".+\\+pid\\d+\\+9f8a1c2e");
    }

    @Test
    @DisplayName("makeHolder handles short clientId without crashing")
    void makeHolder_shortClientId() {
        String holder = RemoteLockManager.makeHolder("abc");
        assertThat(holder).contains("+abc");
    }

    @Test
    @DisplayName("makeHolder rejects null clientId")
    void makeHolder_nullClientId_throwsNpe() {
        assertThatThrownBy(() -> RemoteLockManager.makeHolder(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("acquire with null sftp throws NPE")
    void acquire_nullSftp_throwsNpe() {
        assertThatThrownBy(() ->
            RemoteLockManager.acquire(null, "/sftp", "h", "push", 300))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("release with null sftp throws NPE")
    void release_nullSftp_throwsNpe() {
        assertThatThrownBy(() -> RemoteLockManager.release(null, "/sftp"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("read with null sftp throws NPE")
    void read_nullSftp_throwsNpe() {
        assertThatThrownBy(() -> RemoteLockManager.read(null, "/sftp"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("FILE_NAME constant is stable")
    void fileName_isStable() {
        assertThat(RemoteLockManager.FILE_NAME).isEqualTo("lock");
    }
}
