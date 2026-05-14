package io.github.shizuka.sftpsync.sftp;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;

import java.io.IOException;

/**
 * Helper para hacer rename atómico con overwrite vía la extensión
 * {@code posix-rename@openssh.com}.
 *
 * <p>MINA SSHD rechaza {@code SftpClient.rename(src, dst, CopyMode.Overwrite)}
 * cuando el server habla SFTPv3 (lo que OpenSSH negocia por default), porque
 * los rename flags solo están definidos en SFTPv5+. Para sobrescribir atómicamente
 * en SFTPv3, hay que invocar la extensión OpenSSH explícitamente — que es lo que
 * `sshj` hacía automáticamente cuando se pasaba {@code RenameFlags.OVERWRITE}.
 *
 * <p>Si el server no anuncia la extensión (caso extremadamente raro: OpenSSH
 * la trae desde la versión 5.0 de 2008), caemos a {@code remove + rename}
 * estándar que NO es atómico — abrir una pequeña ventana donde el target no
 * existe. Documentado y aceptado para SFTP servers no-OpenSSH.
 *
 * <p><b>Cobertura de tests:</b> ambas ramas (extension + fallback) están
 * validadas vía E2E real contra {@code emberstack/sftp v5.1.71} (que SÍ
 * trae la extensión) y servers que la rechazan (testeado manualmente). No
 * agregamos unit tests con Mockito para no sumar una dependencia de test
 * por una sola clase.
 */
public final class PosixRename {

    private PosixRename() {}

    /**
     * Mueve {@code src} sobre {@code dst} atómicamente, sobrescribiendo si
     * {@code dst} ya existe.
     */
    public static void overwrite(SftpClient sftp, String src, String dst) throws IOException {
        OpenSSHPosixRenameExtension ext = sftp.getExtension(OpenSSHPosixRenameExtension.class);
        if (ext != null && ext.isSupported()) {
            ext.posixRename(src, dst);
            return;
        }
        // Fallback no-atómico para servers sin la extensión.
        if (RemoteManifestStore.statExists(sftp, dst)) {
            sftp.remove(dst);
        }
        sftp.rename(src, dst);
    }
}
