package io.github.shizuka.sftpsync.manifest;

import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.WatchConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestBuilderTest {

    private static SyncConfig configWithIgnore(List<String> ignore, int maxFileSizeMB) {
        return new SyncConfig(
            "test-client",
            new RemoteConfig("h", 22, "u", "/k", "/r", null),
            ignore,
            false,
            maxFileSizeMB,
            WatchConfig.defaults()
        );
    }

    @Test
    @DisplayName("build empty directory produces empty manifest with stable metadata")
    void build_emptyDir_returnsEmptyManifest(@TempDir Path tmp) throws IOException {
        Manifest m = new ManifestBuilder(tmp, configWithIgnore(List.of(), 200), new ScanCache())
            .build();
        assertThat(m.entries()).isEmpty();
        assertThat(m.generatedBy()).isEqualTo("test-client");
    }

    @Test
    @DisplayName("build hashes all regular files and uses normalized forward-slash paths")
    void build_filesPresent_hashesAndNormalizesPaths(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("a.txt"),    "abc", StandardCharsets.UTF_8);
        Files.createDirectories(tmp.resolve("sub"));
        Files.writeString(tmp.resolve("sub").resolve("b.txt"), "xyz", StandardCharsets.UTF_8);

        Manifest m = new ManifestBuilder(tmp, configWithIgnore(List.of(), 200), new ScanCache())
            .build();

        assertThat(m.entries()).hasSize(2);
        assertThat(m.entries()).containsKeys("a.txt", "sub/b.txt");
        assertThat(m.entries().get("a.txt").size()).isEqualTo(3);
        assertThat(m.entries().get("a.txt").sha256())
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("ignore patterns exclude matched files from the manifest")
    void build_ignorePatterns_excludesMatches(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("keep.txt"),     "k", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("skip.log"),     "s", StandardCharsets.UTF_8);
        Files.createDirectories(tmp.resolve("target"));
        Files.writeString(tmp.resolve("target").resolve("Skip.class"),
                          "c", StandardCharsets.UTF_8);

        Manifest m = new ManifestBuilder(
                tmp,
                configWithIgnore(List.of("*.log", "target/"), 200),
                new ScanCache())
            .build();

        assertThat(m.entries()).containsOnlyKeys("keep.txt");
    }

    @Test
    @DisplayName("files exceeding maxFileSizeMB are skipped silently from the manifest")
    void build_oversizeFile_skipped(@TempDir Path tmp) throws IOException {
        // maxFileSizeMB = 1 → archivos de >1 MB se skipean. Un buffer de 2 MB
        // es suficiente para cruzar el límite sin ralentizar el test.
        // (Nota: no podemos usar maxFileSizeMB = 0 porque el compact constructor
        //  de SyncConfig lo interpreta como "missing" y lo reemplaza con 200.)
        Files.writeString(tmp.resolve("small.txt"), "ok", StandardCharsets.UTF_8);
        Files.write(tmp.resolve("big.bin"), new byte[2 * 1024 * 1024]);

        Manifest m = new ManifestBuilder(tmp, configWithIgnore(List.of(), 1), new ScanCache())
            .build();

        assertThat(m.entries()).containsOnlyKeys("small.txt");
    }

    @Test
    @DisplayName("scan cache populates after first build and survives second build")
    void build_twice_secondBuildReuseCacheHashes(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("a.txt"), "abc", StandardCharsets.UTF_8);

        ScanCache cache = new ScanCache();
        Manifest first = new ManifestBuilder(tmp, configWithIgnore(List.of(), 200), cache)
            .build();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(first.entries().get("a.txt").sha256())
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        // Segundo build: el cache debería tener la entry y reusarla.
        long mtime = Files.getLastModifiedTime(tmp.resolve("a.txt")).toMillis();
        assertThat(cache.lookup("a.txt", mtime, 3L)).isPresent();

        Manifest second = new ManifestBuilder(tmp, configWithIgnore(List.of(), 200), cache)
            .build();
        assertThat(second.entries()).isEqualTo(first.entries());
    }

    @Test
    @DisplayName("retainOnly purges cache entries for deleted files")
    void build_fileRemoved_cacheEntryPurged(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("keep.txt"),  "k", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("delete.txt"), "d", StandardCharsets.UTF_8);

        ScanCache cache = new ScanCache();
        new ManifestBuilder(tmp, configWithIgnore(List.of(), 200), cache).build();
        assertThat(cache.size()).isEqualTo(2);

        Files.delete(tmp.resolve("delete.txt"));
        new ManifestBuilder(tmp, configWithIgnore(List.of(), 200), cache).build();

        assertThat(cache.size()).isEqualTo(1);
    }
}
