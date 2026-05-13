package io.github.shizuka.sftpsync.sftp;

import io.github.shizuka.sftpsync.config.RemoteConfig;
import io.github.shizuka.sftpsync.sftp.SftpSession.HostKeyMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitarios de SftpSession sin necesidad de un servidor real.
 *
 * <p>La conexión real contra emberstack/sftp se valida manualmente con
 * {@code sftp-sync ping}. Para tests automatizados contra un server real
 * (testcontainers) ver el TODO en el design doc.
 */
class SftpSessionTest {

    @Test
    @DisplayName("open with null config throws NPE")
    void open_nullConfig_throwsNpe() {
        assertThatThrownBy(() -> SftpSession.open(null, HostKeyMode.STRICT))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("open with null mode throws NPE")
    void open_nullMode_throwsNpe() {
        RemoteConfig cfg = new RemoteConfig("h", 22, "u", "/k", "/r", null);
        assertThatThrownBy(() -> SftpSession.open(cfg, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("open with missing key file fails fast with clear IOException")
    void open_keyFileMissing_throwsIoException(@TempDir Path tmp) {
        RemoteConfig cfg = new RemoteConfig(
            "127.0.0.1", 22, "u",
            tmp.resolve("nonexistent-key").toString(),
            "/r", null
        );
        assertThatThrownBy(() -> SftpSession.open(cfg, HostKeyMode.INSECURE))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("no encontrada");
    }

    @Test
    @DisplayName("open with no auth method (no key, no password) throws IOException")
    void open_noAuthMethod_throwsIoException() {
        RemoteConfig cfg = new RemoteConfig("h", 22, "u", "", "/r", null);
        assertThatThrownBy(() -> SftpSession.open(cfg, HostKeyMode.INSECURE))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("autenticación");
    }

    @Test
    @DisplayName("open against closed port fails with IOException (connection refused)")
    void open_closedPort_throwsIoException(@TempDir Path tmp) throws IOException {
        // Crear un archivo que actúe como "key" (existe + es legible).
        // No hace falta que sea una clave SSH válida — el connect falla antes.
        Path fakeKey = tmp.resolve("fake-key");
        Files.writeString(fakeKey, "not-a-real-key");

        RemoteConfig cfg = new RemoteConfig(
            "127.0.0.1", 1, "u", fakeKey.toString(), "/r", null
        );

        // Puerto 1 está cerrado en cualquier máquina razonable. INSECURE para
        // saltearnos la verificación de host key.
        assertThatThrownBy(() -> SftpSession.open(cfg, HostKeyMode.INSECURE))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("open with password against closed port also fails with IOException")
    void open_passwordAuthClosedPort_throwsIoException() {
        RemoteConfig cfg = new RemoteConfig(
            "127.0.0.1", 1, "u", null, "/r", "secret"
        );
        assertThatThrownBy(() -> SftpSession.open(cfg, HostKeyMode.INSECURE))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("RemoteConfig.hasKey reflects keyPath presence")
    void remoteConfig_hasKey_reflectsKeyPath() {
        assertThat(new RemoteConfig("h", 22, "u", "/k", "/r", null).hasKey()).isTrue();
        assertThat(new RemoteConfig("h", 22, "u", "",   "/r", null).hasKey()).isFalse();
        assertThat(new RemoteConfig("h", 22, "u", null, "/r", null).hasKey()).isFalse();
    }

    @Test
    @DisplayName("RemoteConfig.hasPassword reflects password presence")
    void remoteConfig_hasPassword_reflectsPassword() {
        assertThat(new RemoteConfig("h", 22, "u", null, "/r", "p").hasPassword()).isTrue();
        assertThat(new RemoteConfig("h", 22, "u", null, "/r", "").hasPassword()).isFalse();
        assertThat(new RemoteConfig("h", 22, "u", null, "/r", null).hasPassword()).isFalse();
    }

    @Test
    @DisplayName("HostKeyMode enum has STRICT and INSECURE values")
    void hostKeyMode_hasExpectedValues() {
        assertThat(HostKeyMode.values()).containsExactly(
            HostKeyMode.STRICT, HostKeyMode.INSECURE);
    }
}
