package io.github.shizuka.sftpsync.watcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateStoreTest {

    @Test
    @DisplayName("save/loadOrNull round-trip preserves all fields")
    void saveAndLoad_roundTrip(@TempDir Path tmp) throws IOException {
        WatchState original = new WatchState(
            "2026-05-13T14:21:30Z",
            "2026-05-13T14:21:55Z",
            new WatchState.Summary(3, 1, 0),
            true,
            List.of("err1", "err2"));

        StateStore.save(tmp, original);
        WatchState loaded = StateStore.loadOrNull(tmp);

        assertThat(loaded).isEqualTo(original);
    }

    @Test
    @DisplayName("loadOrNull returns null when state.json does not exist")
    void loadOrNull_missing_returnsNull(@TempDir Path tmp) {
        assertThat(StateStore.loadOrNull(tmp)).isNull();
    }

    @Test
    @DisplayName("loadIfFresh returns state when lastRemoteCheckAt is recent")
    void loadIfFresh_recent_returnsState(@TempDir Path tmp) throws IOException {
        WatchState fresh = new WatchState(
            Instant.now().toString(),
            Instant.now().toString(),
            new WatchState.Summary(0, 0, 0),
            true,
            List.of());
        StateStore.save(tmp, fresh);

        assertThat(StateStore.loadIfFresh(tmp, 30)).isNotNull();
    }

    @Test
    @DisplayName("loadIfFresh returns null when lastRemoteCheckAt is stale")
    void loadIfFresh_stale_returnsNull(@TempDir Path tmp) throws IOException {
        WatchState stale = new WatchState(
            Instant.now().minusSeconds(3600).toString(),
            Instant.now().toString(),
            new WatchState.Summary(0, 0, 0),
            true,
            List.of());
        StateStore.save(tmp, stale);

        assertThat(StateStore.loadIfFresh(tmp, 30)).isNull();
    }

    @Test
    @DisplayName("loadIfFresh returns null on corrupt timestamp")
    void loadIfFresh_corruptTimestamp_returnsNull(@TempDir Path tmp) throws IOException {
        WatchState corrupt = new WatchState(
            "not-a-timestamp",
            "2026-05-13T14:21:55Z",
            new WatchState.Summary(0, 0, 0),
            true,
            List.of());
        StateStore.save(tmp, corrupt);

        assertThat(StateStore.loadIfFresh(tmp, 30)).isNull();
    }
}
