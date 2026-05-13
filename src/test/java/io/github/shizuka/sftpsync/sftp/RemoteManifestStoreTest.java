package io.github.shizuka.sftpsync.sftp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitarios de RemoteManifestStore sin necesidad de un servidor real.
 *
 * <p>El round-trip real (load/save vía SFTP) se valida manualmente contra
 * emberstack/sftp con {@code sftp-sync remote-manifest [--put]}.
 */
class RemoteManifestStoreTest {

    @Test
    @DisplayName("syncDir joins remote root with .sync")
    void syncDir_joinsRootWithDotSync() {
        assertThat(RemoteManifestStore.syncDir("/sftp")).isEqualTo("/sftp/.sync");
        assertThat(RemoteManifestStore.syncDir("/upload/project-x"))
            .isEqualTo("/upload/project-x/.sync");
    }

    @Test
    @DisplayName("syncDir tolerates trailing slash in remote root")
    void syncDir_tolerantToTrailingSlash() {
        assertThat(RemoteManifestStore.syncDir("/sftp/")).isEqualTo("/sftp/.sync");
    }

    @Test
    @DisplayName("manifestPath points to .sync/manifest.json under remote root")
    void manifestPath_pointsToCorrectFile() {
        assertThat(RemoteManifestStore.manifestPath("/sftp"))
            .isEqualTo("/sftp/.sync/manifest.json");
    }

    @Test
    @DisplayName("tmpPath uses .tmp suffix for atomic-rename staging")
    void tmpPath_usesTmpSuffix() {
        assertThat(RemoteManifestStore.tmpPath("/sftp"))
            .isEqualTo("/sftp/.sync/manifest.json.tmp");
    }

    @Test
    @DisplayName("joinRemote produces a single slash between components")
    void joinRemote_singleSlashBetweenComponents() {
        assertThat(RemoteManifestStore.joinRemote("/a",   "b"))  .isEqualTo("/a/b");
        assertThat(RemoteManifestStore.joinRemote("/a/",  "b"))  .isEqualTo("/a/b");
        assertThat(RemoteManifestStore.joinRemote("/a",   "/b")) .isEqualTo("/a/b");
        assertThat(RemoteManifestStore.joinRemote("/a/",  "/b")) .isEqualTo("/a/b");
    }

    @Test
    @DisplayName("joinRemote handles empty base")
    void joinRemote_emptyBase() {
        assertThat(RemoteManifestStore.joinRemote("", "foo")).isEqualTo("foo");
    }

    @Test
    @DisplayName("exists with null sftp throws NPE")
    void exists_nullSftp_throwsNpe() {
        assertThatThrownBy(() -> RemoteManifestStore.exists(null, "/sftp"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("load with null sftp throws NPE")
    void load_nullSftp_throwsNpe() {
        assertThatThrownBy(() -> RemoteManifestStore.load(null, "/sftp"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("save with null sftp throws NPE")
    void save_nullSftp_throwsNpe() {
        assertThatThrownBy(() -> RemoteManifestStore.save(null, "/sftp", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constants are stable")
    void constants_areStable() {
        assertThat(RemoteManifestStore.DIR_NAME).isEqualTo(".sync");
        assertThat(RemoteManifestStore.FILE_NAME).isEqualTo("manifest.json");
        assertThat(RemoteManifestStore.TMP_NAME).isEqualTo("manifest.json.tmp");
    }
}
