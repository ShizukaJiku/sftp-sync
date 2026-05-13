package io.github.shizuka.sftpsync.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestStoreTest {

    @Test
    @DisplayName("load throws NoSuchFile when manifest file is missing")
    void load_missingFile_throwsNoSuchFile(@TempDir Path tmp) {
        assertThatThrownBy(() -> ManifestStore.load(tmp))
            .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    @DisplayName("save then load round-trip preserves all entries")
    void roundTrip_multipleEntries_preservesData(@TempDir Path tmp) throws IOException {
        Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        entries.put("README.md",     new ManifestEntry("aa00", 100));
        entries.put("src/Main.java", new ManifestEntry("bb11", 200));
        entries.put("docs/x.md",     new ManifestEntry("cc22", 300));

        Manifest original = Manifest.of("test-client-uuid", entries);
        ManifestStore.save(tmp, original);

        Manifest loaded = ManifestStore.load(tmp);
        assertThat(loaded.version()).isEqualTo(Manifest.CURRENT_VERSION);
        assertThat(loaded.generatedBy()).isEqualTo("test-client-uuid");
        assertThat(loaded.entries()).hasSize(3);
        assertThat(loaded.entries().get("README.md").sha256()).isEqualTo("aa00");
        assertThat(loaded.entries().get("README.md").size()).isEqualTo(100);
        assertThat(loaded.entries().get("src/Main.java").sha256()).isEqualTo("bb11");
        assertThat(loaded.entries().get("docs/x.md").size()).isEqualTo(300);
    }

    @Test
    @DisplayName("entries in saved manifest are sorted alphabetically by path")
    void save_unsortedInput_writesSortedOutput(@TempDir Path tmp) throws IOException {
        Map<String, ManifestEntry> unsorted = new LinkedHashMap<>();
        unsorted.put("zeta.txt",  new ManifestEntry("zz", 1));
        unsorted.put("alpha.txt", new ManifestEntry("aa", 2));
        unsorted.put("beta.txt",  new ManifestEntry("bb", 3));

        Manifest m = Manifest.of("c", unsorted);
        ManifestStore.save(tmp, m);

        String json = Files.readString(ManifestStore.path(tmp));
        int alphaIdx = json.indexOf("alpha.txt");
        int betaIdx  = json.indexOf("beta.txt");
        int zetaIdx  = json.indexOf("zeta.txt");
        assertThat(alphaIdx).isLessThan(betaIdx).isPositive();
        assertThat(betaIdx).isLessThan(zetaIdx);
    }

    @Test
    @DisplayName("empty manifest serializes and round-trips")
    void roundTrip_emptyManifest_works(@TempDir Path tmp) throws IOException {
        Manifest empty = Manifest.empty("c");
        ManifestStore.save(tmp, empty);

        Manifest loaded = ManifestStore.load(tmp);
        assertThat(loaded.entries()).isEmpty();
        assertThat(loaded.generatedBy()).isEqualTo("c");
    }

    @Test
    @DisplayName("ManifestEntry rejects negative size")
    void manifestEntry_negativeSize_throws() {
        assertThatThrownBy(() -> new ManifestEntry("aa", -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ManifestEntry rejects blank sha256")
    void manifestEntry_blankSha256_throws() {
        assertThatThrownBy(() -> new ManifestEntry("", 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManifestEntry(null, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
