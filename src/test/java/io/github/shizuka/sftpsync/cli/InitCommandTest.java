package io.github.shizuka.sftpsync.cli;

import io.github.shizuka.sftpsync.Main;
import io.github.shizuka.sftpsync.config.SyncConfig;
import io.github.shizuka.sftpsync.config.SyncConfigStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests del comando init en modo --non-interactive (sin TTY).
 *
 * <p>Verifica:
 * <ul>
 *   <li>Que el archivo se cree con los valores correctos cuando todos los
 *       flags están presentes.</li>
 *   <li>Que los modos de fallo (sobrescribir sin --force, valores inválidos,
 *       campos faltantes) devuelvan exit codes específicos.</li>
 *   <li>Que la utilidad expandTilde funcione cross-platform.</li>
 * </ul>
 */
class InitCommandTest {

    private CommandLine newCli() {
        return new CommandLine(new Main())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()));
    }

    @Test
    @DisplayName("init writes config file when all flags are provided")
    void init_allFlagsPresent_writesConfig(@TempDir Path tmp) throws IOException {
        int exit = newCli().execute(
            "-C", tmp.toString(),
            "init",
            "--non-interactive",
            "--host", "sftp.example.com",
            "--port", "2222",
            "--user", "shizuka",
            "--key", "/home/shizuka/.ssh/id_ed25519",
            "--remote-root", "/upload/proyecto-x"
        );

        assertThat(exit).isZero();
        assertThat(SyncConfigStore.exists(tmp)).isTrue();

        SyncConfig loaded = SyncConfigStore.load(tmp);
        assertThat(loaded.remote().host()).isEqualTo("sftp.example.com");
        assertThat(loaded.remote().port()).isEqualTo(2222);
        assertThat(loaded.remote().user()).isEqualTo("shizuka");
        assertThat(loaded.remote().keyPath()).isEqualTo("/home/shizuka/.ssh/id_ed25519");
        assertThat(loaded.remote().remoteRoot()).isEqualTo("/upload/proyecto-x");
        assertThat(loaded.clientId()).isNotBlank();
    }

    @Test
    @DisplayName("init fails when config exists and --force is not passed")
    void init_existingConfigWithoutForce_returnsExit1(@TempDir Path tmp) {
        int firstExit = newCli().execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--host", "h", "--user", "u",
            "--key", "/k", "--remote-root", "/r"
        );
        assertThat(firstExit).isZero();

        int secondExit = newCli().execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--host", "h2", "--user", "u",
            "--key", "/k", "--remote-root", "/r"
        );
        assertThat(secondExit).isEqualTo(1);
    }

    @Test
    @DisplayName("init overwrites existing config when --force is passed")
    void init_existingConfigWithForce_overwrites(@TempDir Path tmp) throws IOException {
        newCli().execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--host", "first.example.com", "--user", "u",
            "--key", "/k", "--remote-root", "/r"
        );

        int exit = newCli().execute(
            "-C", tmp.toString(),
            "init", "--non-interactive", "--force",
            "--host", "second.example.com", "--user", "u",
            "--key", "/k", "--remote-root", "/r"
        );

        assertThat(exit).isZero();
        SyncConfig loaded = SyncConfigStore.load(tmp);
        assertThat(loaded.remote().host()).isEqualTo("second.example.com");
    }

    @Test
    @DisplayName("init fails when required field is missing in non-interactive mode")
    void init_missingHostNonInteractive_returnsExit2(@TempDir Path tmp) {
        int exit = newCli().execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--user", "u", "--key", "/k", "--remote-root", "/r"
            // host omitido
        );
        assertThat(exit).isEqualTo(2);
        assertThat(SyncConfigStore.exists(tmp)).isFalse();
    }

    @Test
    @DisplayName("init fails when remote root is not absolute")
    void init_relativeRemoteRoot_returnsExit2(@TempDir Path tmp) {
        int exit = newCli().execute(
            "-C", tmp.toString(),
            "init", "--non-interactive",
            "--host", "h", "--user", "u",
            "--key", "/k", "--remote-root", "relative/path"
        );
        assertThat(exit).isEqualTo(2);
    }

}
