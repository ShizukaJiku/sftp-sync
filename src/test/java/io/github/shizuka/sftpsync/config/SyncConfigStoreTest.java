package io.github.shizuka.sftpsync.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncConfigStoreTest {

    @Test
    @DisplayName("exists returns false when config file is missing")
    void exists_missingFile_returnsFalse(@TempDir Path tmp) {
        assertThat(SyncConfigStore.exists(tmp)).isFalse();
    }

    @Test
    @DisplayName("load throws NoSuchFileException when config file is missing")
    void load_missingFile_throwsNoSuchFile(@TempDir Path tmp) {
        assertThatThrownBy(() -> SyncConfigStore.load(tmp))
            .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    @DisplayName("save then load round-trip preserves all fields")
    void roundTrip_allFieldsSet_preservesEverything(@TempDir Path tmp) throws IOException {
        SyncConfig original = new SyncConfig(
            "fixed-test-client-id-1234",
            new RemoteConfig("test.example.com", 2222, "alice",
                             "/home/alice/.ssh/id_ed25519", "/upload/test-project", null),
            List.of("custom/pattern", "*.tmp"),
            false,
            50,
            new WatchConfig(60, 1000)
        );

        SyncConfigStore.save(tmp, original);

        assertThat(SyncConfigStore.exists(tmp)).isTrue();
        assertThat(SyncConfigStore.configPath(tmp))
            .isEqualTo(tmp.resolve(".sync").resolve("config.json"));

        SyncConfig loaded = SyncConfigStore.load(tmp);
        assertThat(loaded.clientId()).isEqualTo("fixed-test-client-id-1234");
        assertThat(loaded.remote().host()).isEqualTo("test.example.com");
        assertThat(loaded.remote().port()).isEqualTo(2222);
        assertThat(loaded.remote().user()).isEqualTo("alice");
        assertThat(loaded.remote().keyPath()).isEqualTo("/home/alice/.ssh/id_ed25519");
        assertThat(loaded.remote().remoteRoot()).isEqualTo("/upload/test-project");
        assertThat(loaded.maxFileSizeMB()).isEqualTo(50);
        assertThat(loaded.useGitignore()).isFalse();
        assertThat(loaded.watch().pollIntervalSeconds()).isEqualTo(60);
        assertThat(loaded.watch().debounceMs()).isEqualTo(1000);
        assertThat(loaded.ignore()).containsExactly("custom/pattern", "*.tmp");
    }

    @Test
    @DisplayName("save creates .sync directory if it does not exist")
    void save_missingSyncDir_createsIt(@TempDir Path tmp) throws IOException {
        Path syncDir = tmp.resolve(".sync");
        assertThat(syncDir).doesNotExist();

        SyncConfigStore.save(tmp, makeConfig("h"));

        assertThat(syncDir).isDirectory();
        assertThat(syncDir.resolve("config.json")).exists();
    }

    @Test
    @DisplayName("save twice overwrites the existing config file")
    void save_calledTwice_overwritesFile(@TempDir Path tmp) throws IOException {
        SyncConfigStore.save(tmp, makeConfig("first"));
        SyncConfigStore.save(tmp, makeConfig("second"));

        SyncConfig loaded = SyncConfigStore.load(tmp);
        assertThat(loaded.remote().host()).isEqualTo("second");
    }

    @Test
    @DisplayName("withDefaults generates a valid UUID for clientId")
    void withDefaults_called_generatesValidUuid() {
        SyncConfig c = SyncConfig.withDefaults();
        // Throws IllegalArgumentException si no es UUID válido.
        UUID.fromString(c.clientId());
    }

    @Test
    @DisplayName("written file is valid UTF-8 and preserves non-ASCII characters")
    void save_nonAsciiHost_preservesUtf8(@TempDir Path tmp) throws IOException {
        SyncConfig c = new SyncConfig(
            "id", new RemoteConfig("úñíçødé.example.com", 22, "u", "/k", "/r", null),
            List.of(), true, 100, WatchConfig.defaults()
        );
        SyncConfigStore.save(tmp, c);

        String content = Files.readString(SyncConfigStore.configPath(tmp));
        assertThat(content).contains("úñíçødé.example.com");
    }

    @Test
    @DisplayName("compact constructor applies port default when port is zero")
    void remoteConfig_zeroPort_defaultsTo22() {
        RemoteConfig r = new RemoteConfig("h", 0, "u", "/k", "/r", null);
        assertThat(r.port()).isEqualTo(22);
    }

    @Test
    @DisplayName("compact constructor applies watch defaults when fields are zero")
    void watchConfig_zeroFields_appliesDefaults() {
        WatchConfig w = new WatchConfig(0, 0);
        assertThat(w.pollIntervalSeconds()).isEqualTo(30);
        assertThat(w.debounceMs()).isEqualTo(500);
    }

    @Test
    @DisplayName("compact constructor regenerates clientId when blank")
    void syncConfig_blankClientId_regenerates() {
        SyncConfig c = new SyncConfig(
            "", new RemoteConfig("h", 22, "u", "/k", "/r", null),
            List.of(), true, 200, WatchConfig.defaults()
        );
        // Throws si no es UUID válido.
        UUID.fromString(c.clientId());
    }

    private static SyncConfig makeConfig(String hostMarker) {
        return new SyncConfig(
            UUID.randomUUID().toString(),
            new RemoteConfig(hostMarker, 22, "u", "/k", "/r", null),
            List.of(),
            true,
            200,
            WatchConfig.defaults()
        );
    }
}
