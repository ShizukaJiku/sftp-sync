package io.github.shizuka.sftpsync.diff;

import io.github.shizuka.sftpsync.manifest.Manifest;
import io.github.shizuka.sftpsync.manifest.ManifestEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre toda la tabla de verdad de docs/design.md §7. Cada test es un row de la tabla.
 */
class ThreeWayDifferTest {

    private static Manifest manifest(Map<String, String> pathToHash) {
        Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        pathToHash.forEach((p, h) -> entries.put(p, new ManifestEntry(h, 1L)));
        return Manifest.of("test-client", entries);
    }

    private static Map<String, String> map(String... kvs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put(kvs[i], kvs[i + 1]);
        return m;
    }

    @Test
    @DisplayName("(B=x, L=x, R=x) → unchanged")
    void allThreeEqual_unchanged() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map("f", "x")), manifest(map("f", "x")));
        assertThat(cs.unchanged()).containsExactly("f");
        assertThat(cs.isClean()).isTrue();
    }

    @Test
    @DisplayName("(B=x, L=y, R=x) → upload")
    void localChanged_upload() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map("f", "y")), manifest(map("f", "x")));
        assertThat(cs.toUpload()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=x, L=x, R=y) → download")
    void remoteChanged_download() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map("f", "x")), manifest(map("f", "y")));
        assertThat(cs.toDownload()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=x, L=y, R=y) → unchanged (both moved to same content)")
    void bothChangedSameContent_unchanged() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map("f", "y")), manifest(map("f", "y")));
        assertThat(cs.unchanged()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=x, L=y, R=z) → conflict")
    void bothChangedDifferent_conflict() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map("f", "y")), manifest(map("f", "z")));
        assertThat(cs.conflicts()).containsExactly("f");
        assertThat(cs.hasConflicts()).isTrue();
    }

    @Test
    @DisplayName("(B=x, L=-, R=x) → delete remote")
    void localDeletedRemoteUnchanged_deleteRemote() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map()), manifest(map("f", "x")));
        assertThat(cs.toDeleteRemote()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=x, L=x, R=-) → delete local")
    void remoteDeletedLocalUnchanged_deleteLocal() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map("f", "x")), manifest(map()));
        assertThat(cs.toDeleteLocal()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=x, L=-, R=-) → skip (both deleted)")
    void bothDeleted_skip() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map()), manifest(map()));
        assertThat(cs.isClean()).isTrue();
        assertThat(cs.unchanged()).isEmpty();
    }

    @Test
    @DisplayName("(B=x, L=y, R=-) → conflict (local edited but remote deleted)")
    void localEditedRemoteDeleted_conflict() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map("f", "y")), manifest(map()));
        assertThat(cs.conflicts()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=x, L=-, R=y) → conflict (remote edited but local deleted)")
    void remoteEditedLocalDeleted_conflict() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("f", "x")), manifest(map()), manifest(map("f", "y")));
        assertThat(cs.conflicts()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=-, L=x, R=-) → upload (new local file)")
    void newLocal_upload() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map()), manifest(map("f", "x")), manifest(map()));
        assertThat(cs.toUpload()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=-, L=-, R=x) → download (new remote file)")
    void newRemote_download() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map()), manifest(map()), manifest(map("f", "x")));
        assertThat(cs.toDownload()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=-, L=x, R=x) → unchanged (both created same content)")
    void bothNewSameContent_unchanged() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map()), manifest(map("f", "x")), manifest(map("f", "x")));
        assertThat(cs.unchanged()).containsExactly("f");
    }

    @Test
    @DisplayName("(B=-, L=x, R=y) → conflict (both new with different content)")
    void bothNewDifferent_conflict() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map()), manifest(map("f", "x")), manifest(map("f", "y")));
        assertThat(cs.conflicts()).containsExactly("f");
    }

    @Test
    @DisplayName("multiple files: each classified independently")
    void multipleFiles_classifiedIndependently() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map("a", "x", "b", "y", "c", "z")),
            manifest(map("a", "x", "b", "Y2", "d", "new")),
            manifest(map("a", "X2", "b", "y")));
        assertThat(cs.toDownload()).containsExactly("a");     // (x,x,X2)
        assertThat(cs.toUpload()).containsExactly("b", "d");  // (y,Y2,y) and (-,new,-)
        assertThat(cs.toDeleteRemote()).isEmpty();            // c is gone from local AND remote → both deleted
        assertThat(cs.toDeleteLocal()).isEmpty();
        assertThat(cs.unchanged()).isEmpty();
    }

    @Test
    @DisplayName("empty manifests produce clean changeset")
    void allEmpty_clean() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map()), manifest(map()), manifest(map()));
        assertThat(cs.isClean()).isTrue();
        assertThat(cs.pendingOperations()).isZero();
    }

    @Test
    @DisplayName("ChangeSet sorts paths in each set")
    void changeSet_sortsPaths() {
        ChangeSet cs = ThreeWayDiffer.diff(
            manifest(map()),
            manifest(map("z", "1", "a", "2", "m", "3")),
            manifest(map()));
        assertThat(cs.toUpload()).containsExactly("a", "m", "z");
    }
}
