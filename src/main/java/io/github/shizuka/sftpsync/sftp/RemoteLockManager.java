package io.github.shizuka.sftpsync.sftp;

import com.fasterxml.jackson.jr.ob.JSON;
import io.github.shizuka.sftpsync.util.Hostname;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RenameFlags;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Maneja el lock remoto en {@code <remoteRoot>/.sync/lock} usando la atomicidad
 * de {@code SSH_FXP_OPEN} con flags {@code CREAT|EXCL|WRITE}.
 *
 * <p><b>Garantía de exclusión mutua:</b> el protocolo SFTP define que con
 * {@code SSH_FXF_CREAT | SSH_FXF_EXCL} el server falla atómicamente si el archivo
 * ya existe. OpenSSH (base de emberstack) implementa esto correctamente, así que
 * solo un cliente gana la carrera.
 *
 * <p><b>API:</b> {@link #acquire}/{@link #release} cubren el caso simple sin
 * huérfanos; {@link #acquireOrSteal} agrega CAS sobre locks vencidos (Apéndice D
 * del design). Para mantener el TTL fresco durante operaciones largas, ver
 * {@link LockHeartbeat}.
 *
 * <p><b>API:</b> stateless, todos los métodos estáticos. La sesión SSH es del caller.
 */
public final class RemoteLockManager {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteLockManager.class);

    /** Nombre del archivo de lock dentro de {@code .sync/}. */
    public static final String FILE_NAME = "lock";

    /** Nombre del archivo temporal usado para el CAS de steal de huérfanos. */
    public static final String NEW_NAME = "lock.new";

    private static final JSON JSON_PRETTY = JSON.std.with(JSON.Feature.PRETTY_PRINT_OUTPUT);

    private RemoteLockManager() {}

    /** Path absoluto remoto a {@code <remoteRoot>/.sync/lock}. */
    public static String lockPath(String remoteRoot) {
        return RemoteManifestStore.joinRemote(
            RemoteManifestStore.syncDir(remoteRoot), FILE_NAME);
    }

    /** Path absoluto remoto al archivo temporal del CAS de steal. */
    public static String lockNewPath(String remoteRoot) {
        return RemoteManifestStore.joinRemote(
            RemoteManifestStore.syncDir(remoteRoot), NEW_NAME);
    }

    /** {@code true} si el lock es huérfano según la regla {@code now - lastHeartbeatAt > ttl}. */
    public static boolean isOrphan(LockInfo lock) {
        return isOrphan(lock, Instant.now());
    }

    /** Variante con clock inyectable (para tests). */
    static boolean isOrphan(LockInfo lock, Instant now) {
        try {
            Instant lastHb = Instant.parse(lock.lastHeartbeatAt());
            return Duration.between(lastHb, now).getSeconds() > lock.ttlSeconds();
        } catch (DateTimeParseException e) {
            // Lock con timestamp corrupto: lo tratamos como huérfano para que se pueda recuperar.
            return true;
        }
    }

    /**
     * Construye un identificador de holder humano-legible.
     * Formato: {@code <hostname>+pid<pid>+<clientIdShort>}, donde {@code clientIdShort}
     * son los primeros 8 chars del UUID.
     */
    public static String makeHolder(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        long pid = ProcessHandle.current().pid();
        String shortId = clientId.length() >= 8 ? clientId.substring(0, 8) : clientId;
        return Hostname.get() + "+pid" + pid + "+" + shortId;
    }

    /**
     * Intenta crear el lock atómicamente. Si ya existe, lanza {@link LockHeldException}
     * con la info del holder actual (best effort — si la lectura del lock falla,
     * el mensaje degrada a "lock existe pero no se pudo leer").
     *
     * <p>Crea {@code <remoteRoot>/.sync/} si no existe.
     *
     * @throws LockHeldException si el lock ya está tomado.
     * @throws IOException       si falla otra operación SFTP (transporte, permisos).
     */
    public static LockInfo acquire(SFTPClient sftp, String remoteRoot,
                                   String holder, String operation,
                                   int ttlSeconds) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(operation, "operation");

        sftp.mkdirs(RemoteManifestStore.syncDir(remoteRoot));

        String path = lockPath(remoteRoot);
        LockInfo lock = LockInfo.now(holder, operation, ttlSeconds);
        byte[] bytes = (JSON_PRETTY.asString(lock) + "\n").getBytes(StandardCharsets.UTF_8);

        try {
            try (RemoteFile rf = sftp.open(path,
                    EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE));
                 OutputStream os = rf.new RemoteFileOutputStream()) {
                os.write(bytes);
            }
            return lock;
        } catch (SFTPException e) {
            // SFTPv3 (OpenSSH) devuelve FAILURE genérico cuando EXCL choca con un
            // archivo existente. No hay forma 100% confiable de distinguirlo de
            // otros errores, así que intentamos leer el lock para confirmar.
            LockInfo existing = tryRead(sftp, remoteRoot);
            if (existing != null) {
                throw new LockHeldException(existing, e);
            }
            throw e;
        }
    }

    /**
     * Libera el lock borrando el archivo. Idempotente: si el lock no existe,
     * no hace nada y retorna {@code false}.
     *
     * <p>No verifica que el caller sea el holder actual — esa validación se hace
     * un nivel arriba si se quiere (ej: PushCmd lee el lock antes de release y se
     * niega a borrar uno ajeno).
     *
     * @return {@code true} si había un lock y se borró, {@code false} si no había nada.
     */
    public static boolean release(SFTPClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        String path = lockPath(remoteRoot);
        if (sftp.statExistence(path) == null) {
            return false;
        }
        sftp.rm(path);
        return true;
    }

    /**
     * Lee el lock actual si existe. Retorna {@code null} si no hay lock.
     * Diferente de {@code load} de otros stores que tiran {@code NoSuchFileException}:
     * acá "no hay lock" es un estado normal y esperado.
     *
     * @throws IOException si hay error de I/O o JSON corrupto.
     */
    public static LockInfo read(SFTPClient sftp, String remoteRoot) throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(remoteRoot, "remoteRoot");
        String path = lockPath(remoteRoot);
        if (sftp.statExistence(path) == null) {
            return null;
        }
        byte[] bytes;
        try (RemoteFile rf = sftp.open(path, EnumSet.of(OpenMode.READ));
             InputStream is = rf.new RemoteFileInputStream()) {
            bytes = is.readAllBytes();
        }
        return JSON.std.beanFrom(LockInfo.class, new String(bytes, StandardCharsets.UTF_8));
    }

    /** Variante "swallowing" de {@link #read} para uso en mensajes de error. */
    private static LockInfo tryRead(SFTPClient sftp, String remoteRoot) {
        try {
            return read(sftp, remoteRoot);
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Sobrescribe el lock con {@code newLock} de forma atómica vía {@code .new + posix-rename}.
     * Usado por:
     * <ul>
     *   <li>El heartbeat thread para refrescar {@code lastHeartbeatAt}.</li>
     *   <li>{@link #stealOrphan} para hacer CAS sobre un lock huérfano (Apéndice D).</li>
     * </ul>
     *
     * <p>NO es seguro como acquire inicial — usa {@link #acquire} para eso (que usa
     * {@code CREAT|EXCL}). Este método sobrescribe sin chequear quién es el holder.
     */
    static void writeOverwrite(SFTPClient sftp, String remoteRoot, LockInfo newLock)
            throws IOException {
        String dir = RemoteManifestStore.syncDir(remoteRoot);
        sftp.mkdirs(dir);
        String target = lockPath(remoteRoot);
        String tmp = lockNewPath(remoteRoot);
        byte[] bytes = (JSON_PRETTY.asString(newLock) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            try (RemoteFile rf = sftp.open(tmp,
                    EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC));
                 OutputStream os = rf.new RemoteFileOutputStream()) {
                os.write(bytes);
            }
            sftp.rename(tmp, target, EnumSet.of(RenameFlags.OVERWRITE));
        } catch (IOException e) {
            try {
                if (sftp.statExistence(tmp) != null) sftp.rm(tmp);
            } catch (IOException ignore) { /* nada */ }
            throw e;
        }
    }

    /**
     * Adquiere el lock manejando locks huérfanos. Si el acquire normal falla porque
     * existe un lock vivo (heartbeat fresco) → tira {@link LockHeldException}.
     * Si el lock existente tiene heartbeat expirado → intenta robarlo vía CAS
     * (escribir {@code lock.new} con EXCL + {@code posix-rename} a {@code lock} +
     * verificar que el holder leído == el nuestro).
     *
     * <p>Protocolo del CAS (Apéndice D del design):
     * <ol>
     *   <li>Detectar lock huérfano.</li>
     *   <li>Escribir nuestros datos a {@code .sync/lock.new} con {@code CREAT|EXCL}.</li>
     *   <li>{@code posix-rename} {@code lock.new → lock}.</li>
     *   <li>Releer {@code lock}. Si el holder coincide con el nuestro → ganamos.
     *       Si no → otro cliente ganó la carrera, abortar como lock-held.</li>
     * </ol>
     */
    public static LockInfo acquireOrSteal(SFTPClient sftp, String remoteRoot,
                                          String holder, String operation,
                                          int ttlSeconds) throws IOException {
        try {
            return acquire(sftp, remoteRoot, holder, operation, ttlSeconds);
        } catch (LockHeldException e) {
            if (!isOrphan(e.holder())) {
                throw e; // lock vivo, respetar
            }
            LOG.info("Detectado lock huérfano de {} (lastHeartbeatAt={}). Robándolo.",
                e.holder().holder(), e.holder().lastHeartbeatAt());
            return steal(sftp, remoteRoot, holder, operation, ttlSeconds, e.holder());
        }
    }

    /**
     * Intenta robar un lock huérfano vía CAS. Si otro cliente ganó la carrera,
     * tira {@link LockHeldException} con el nuevo holder.
     */
    private static LockInfo steal(SFTPClient sftp, String remoteRoot,
                                  String newHolder, String operation,
                                  int ttlSeconds, LockInfo prevHolder) throws IOException {
        // Tmp por cliente: evita que dos clientes intentando robar a la vez se pisen
        // mutuamente el lock.new. Cada uno escribe el suyo; el rename atómico decide
        // quién gana al sobrescribir `lock`.
        String tmp = lockNewPath(remoteRoot) + "." + safeForFilename(newHolder);
        String target = lockPath(remoteRoot);
        LockInfo candidate = LockInfo.now(newHolder, operation, ttlSeconds);
        byte[] bytes = (JSON_PRETTY.asString(candidate) + "\n").getBytes(StandardCharsets.UTF_8);

        // Si dejé un tmp propio de un steal anterior que crasheó, lo limpio. Esto solo
        // borra MI tmp (nombre único), no compite con el EXCL de otros clientes.
        try {
            if (sftp.statExistence(tmp) != null) sftp.rm(tmp);
        } catch (IOException ignore) { /* nada */ }

        // 1. Crear mi lock.new.<holder> con EXCL.
        try (RemoteFile rf = sftp.open(tmp,
                EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE));
             OutputStream os = rf.new RemoteFileOutputStream()) {
            os.write(bytes);
        }

        // 2. Rename atómico sobre lock (overwrite). Si dos clientes llegan acá a la
        // vez, el segundo rename pisa al primero — el ganador se determina por el
        // re-read en el paso 3.
        try {
            sftp.rename(tmp, target, EnumSet.of(RenameFlags.OVERWRITE));
        } catch (IOException e) {
            try { sftp.rm(tmp); } catch (IOException ignore) { /* nada */ }
            throw e;
        }

        // 3. Releer y verificar que el lock que quedó es el mío.
        LockInfo afterSteal = read(sftp, remoteRoot);
        if (afterSteal == null || !candidate.holder().equals(afterSteal.holder())
            || !candidate.acquiredAt().equals(afterSteal.acquiredAt())) {
            throw new LockHeldException(
                afterSteal != null ? afterSteal : prevHolder, null);
        }
        return afterSteal;
    }

    /** Reemplaza caracteres que no son seguros para un filename POSIX simple. */
    private static String safeForFilename(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
