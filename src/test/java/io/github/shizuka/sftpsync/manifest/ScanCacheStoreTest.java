package io.github.shizuka.sftpsync.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScanCacheStoreTest {

    @Test
    @DisplayName("loadOrEmpty returns empty cache when file is missing")
    void loadOrEmpty_missingFile_returnsEmpty(@TempDir Path tmp) {
        ScanCache c = ScanCacheStore.loadOrEmpty(tmp);
        assertThat(c.size()).isZero();
    }

    @Test
    @DisplayName("save then loadOrEmpty round-trips entries")
    void roundTrip_entries_preserved(@TempDir Path tmp) throws IOException {
        ScanCache original = new ScanCache();
        original.put("a/b.java", 1000L, 100L, "hash-a");
        original.put("c.txt",    2000L, 200L, "hash-c");

        ScanCacheStore.save(tmp, original);
        ScanCache loaded = ScanCacheStore.loadOrEmpty(tmp);

        assertThat(loaded.size()).isEqualTo(2);
        assertThat(loaded.lookup("a/b.java", 1000L, 100L)).contains("hash-a");
        assertThat(loaded.lookup("c.txt",    2000L, 200L)).contains("hash-c");
    }

    @Test
    @DisplayName("lookup returns empty when mtime differs")
    void lookup_mtimeMismatch_returnsEmpty() {
        ScanCache c = new ScanCache();
        c.put("a", 1000L, 100L, "h");
        assertThat(c.lookup("a", 999L, 100L)).isEmpty();
    }

    @Test
    @DisplayName("lookup returns empty when size differs")
    void lookup_sizeMismatch_returnsEmpty() {
        ScanCache c = new ScanCache();
        c.put("a", 1000L, 100L, "h");
        assertThat(c.lookup("a", 1000L, 99L)).isEmpty();
    }

    @Test
    @DisplayName("lookup returns the cached hash when both mtime and size match")
    void lookup_exactMatch_returnsHash() {
        ScanCache c = new ScanCache();
        c.put("a", 1000L, 100L, "h");
        Optional<String> result = c.lookup("a", 1000L, 100L);
        assertThat(result).contains("h");
    }

    @Test
    @DisplayName("retainOnly removes entries not in the given set")
    void retainOnly_keptSetGiven_removesOthers() {
        ScanCache c = new ScanCache();
        c.put("a", 1L, 1L, "h");
        c.put("b", 1L, 1L, "h");
        c.put("c", 1L, 1L, "h");

        c.retainOnly(Set.of("a", "c"));

        assertThat(c.size()).isEqualTo(2);
        assertThat(c.lookup("a", 1L, 1L)).isPresent();
        assertThat(c.lookup("b", 1L, 1L)).isEmpty();
        assertThat(c.lookup("c", 1L, 1L)).isPresent();
    }

    @Test
    @DisplayName("snapshot returns sorted view of entries")
    void snapshot_returnsSortedView() {
        ScanCache c = new ScanCache();
        c.put("z", 1L, 1L, "h");
        c.put("a", 1L, 1L, "h");
        c.put("m", 1L, 1L, "h");

        var keys = c.snapshot().keySet();
        assertThat(keys).containsExactly("a", "m", "z");
    }
}
