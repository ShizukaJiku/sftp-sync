package io.github.shizuka.sftpsync.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    /** SHA-256 conocido del string vacío: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855. */
    private static final String EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** SHA-256 conocido de "abc": ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad. */
    private static final String ABC_SHA256 =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    @DisplayName("sha256 of empty byte array returns RFC 6234 vector")
    void sha256_emptyByteArray_returnsKnownVector() {
        assertThat(Hashing.sha256(new byte[0])).isEqualTo(EMPTY_SHA256);
    }

    @Test
    @DisplayName("sha256 of \"abc\" returns RFC 6234 vector")
    void sha256_abcByteArray_returnsKnownVector() {
        assertThat(Hashing.sha256("abc".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(ABC_SHA256);
    }

    @Test
    @DisplayName("sha256 of empty file returns RFC 6234 vector")
    void sha256_emptyFile_returnsKnownVector(@TempDir Path tmp) throws IOException {
        Path empty = tmp.resolve("empty.txt");
        Files.createFile(empty);
        assertThat(Hashing.sha256(empty)).isEqualTo(EMPTY_SHA256);
    }

    @Test
    @DisplayName("sha256 of file with \"abc\" returns RFC 6234 vector")
    void sha256_abcFile_returnsKnownVector(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("abc.txt");
        Files.writeString(f, "abc", StandardCharsets.UTF_8);
        assertThat(Hashing.sha256(f)).isEqualTo(ABC_SHA256);
    }

    @Test
    @DisplayName("sha256 of large file (1 MB) is consistent across runs")
    void sha256_largeFile_isDeterministic(@TempDir Path tmp) throws IOException {
        byte[] data = new byte[1024 * 1024];
        Arrays.fill(data, (byte) 0x42);
        Path f = tmp.resolve("blob.bin");
        Files.write(f, data);

        String firstHash = Hashing.sha256(f);
        String secondHash = Hashing.sha256(f);
        assertThat(firstHash).isEqualTo(secondHash);
        assertThat(firstHash).hasSize(64);
    }

    @Test
    @DisplayName("sha256 returns lowercase hex of length 64")
    void sha256_anyInput_returnsLowercaseHexOfLength64() {
        String h = Hashing.sha256("hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("newSha256Digest produces fresh independent instances")
    void newSha256Digest_calledTwice_returnsIndependentDigests() {
        MessageDigest a = Hashing.newSha256Digest();
        MessageDigest b = Hashing.newSha256Digest();
        a.update("abc".getBytes(StandardCharsets.UTF_8));
        // b debe seguir siendo el digest del input vacío, sin contaminarse con a.
        assertThat(Hashing.hex(b.digest())).isEqualTo(EMPTY_SHA256);
        assertThat(Hashing.hex(a.digest())).isEqualTo(ABC_SHA256);
    }

    @Test
    @DisplayName("hex round-trips known SHA-256 vector via streaming digest")
    void hex_streamingDigest_matchesKnownVector() {
        MessageDigest md = Hashing.newSha256Digest();
        md.update("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(Hashing.hex(md.digest())).isEqualTo(ABC_SHA256);
    }

    @Test
    @DisplayName("hex returns empty string for empty input")
    void hex_emptyArray_returnsEmptyString() {
        assertThat(Hashing.hex(new byte[0])).isEmpty();
    }
}
