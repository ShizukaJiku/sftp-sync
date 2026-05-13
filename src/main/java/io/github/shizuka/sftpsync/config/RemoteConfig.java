package io.github.shizuka.sftpsync.config;

/**
 * Datos de conexión al servidor SFTP remoto. Inmutable.
 *
 * <p>El compact constructor aplica defaults para campos no presentes en el JSON
 * leído (notablemente {@code port = 22}). El path de la clave SSH NO se expande
 * acá — la expansión de {@code ~/} se hace en el sitio donde se usa, para que el
 * archivo de config sea legible sin asumir un usuario particular.
 *
 * <p><b>Autenticación:</b> exactamente uno de {@code keyPath} o {@code password}
 * debe estar presente. Si ambos lo están, gana {@code keyPath} (más seguro).
 * Si ninguno, {@link io.github.shizuka.sftpsync.sftp.SftpSession#open}
 * falla con un {@link java.io.IOException} explicativo.
 *
 * <p><b>Aviso de seguridad:</b> guardar {@code password} en {@code config.json}
 * lo deja en plain text en disco. Recomendaciones:
 * <ul>
 *   <li>{@code chmod 600 .sync/config.json}</li>
 *   <li>asegurar que la carpeta NO esté en git</li>
 *   <li>idealmente, usar key auth</li>
 * </ul>
 * Una variante futura ({@code passwordEnv}) leerá el password desde una variable
 * de entorno para evitar guardarlo en el archivo.
 */
public record RemoteConfig(
    String host,
    int port,
    String user,
    String keyPath,
    String remoteRoot,
    String password
) {
    public RemoteConfig {
        if (port == 0) {
            port = 22;
        }
    }

    /** Constructor de conveniencia con port default 22 y solo key auth (sin password). */
    public static RemoteConfig of(String host, String user, String keyPath, String remoteRoot) {
        return new RemoteConfig(host, 22, user, keyPath, remoteRoot, null);
    }

    /** True si {@code keyPath} no es null/vacío. */
    public boolean hasKey() {
        return keyPath != null && !keyPath.isBlank();
    }

    /** True si {@code password} no es null/vacío. */
    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }
}
