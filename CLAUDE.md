# CLAUDE.md

Contexto para Claude Code trabajando en `sftp-sync`. El diseño técnico completo
vive en [`docs/design.md`](docs/design.md) — leélo si vas a tomar decisiones de
arquitectura. Este archivo es solo el "executive briefing" para arrancar rápido.

## Qué es sftp-sync

CLI Java que sincroniza una carpeta entre múltiples PCs usando un servidor SFTP
como fuente de verdad central. Modelo conceptual estilo Git:
- Comandos explícitos: `init`, `status`, `push`, `pull`, `watch`, `resolve`.
- Three-way diff (local actual / remoto actual / base del último sync).
- Conflictos se marcan, **NO** se resuelven automáticamente.
- `watch` SOLO observa y reporta — nunca sincroniza solo. El usuario decide
  siempre cuándo hacer push/pull.

Tamaños objetivo: 10–120 MB de carpeta, cientos de archivos, 2–5 PCs cliente.
Probado contra `emberstack/sftp` v5.1.71 en Docker.

## Stack y decisiones que ya están tomadas

| Componente | Versión | Por qué |
|-----------|---------|---------|
| **GraalVM JDK 21** community | LTS hasta 2031 | Mejor compatibilidad con sshj/BC en native-image que JDK 25 |
| **Maven 3.9** | — | El user prefiere XML explícito sobre Gradle |
| **Apache MINA SSHD** | 2.16.0 | Reemplazó a sshj 0.40 (issue #871: incompatible con BC en native-image) |
| **picocli** | 4.7.7 | + picocli-codegen para reflection en native |
| **jackson-jr-objects** | 2.21.3 | GraalVM-friendly, sin reflection mágica de databind |
| **slf4j-simple** | 2.0.16 | Logging mínimo, zero-config en native |
| **JUnit 5 + AssertJ** | 5.11 / 3.26 | Standard |
| **native-image** desde día 1 | — | NO shadowJar, NO jpackage |

## Dónde estamos en el plan de implementación

El plan completo está en `docs/design.md` sección 13. Estado:

- ✅ **Paso 1**: scaffold Maven + picocli, `--help` funciona
- ✅ **Paso 2**: `SyncConfig` + `init` con prompts (records + jackson-jr)
- ✅ **Paso 3**: `Hashing` + `IgnoreMatcher` + `Manifest` + `ManifestBuilder` + `scan` (debug-only)
- ✅ **Paso 4**: `SftpSession` (sshj wrapper) + `ping` (debug-only) + password auth
- ✅ **Paso 5**: `RemoteManifestStore` (load/save vía SFTP, escritura atómica con posix-rename) + `remote-manifest` (debug-only, `--put` para subir)
- ✅ **Paso 6**: `RemoteLockManager` + `LockInfo` + `LockHeldException` (acquire/release con SSH_FXF_EXCL) + `lock` debug
- ✅ **Paso 7**: `ChangeSet` + `ThreeWayDiffer` (tabla de verdad §7 completa) + `BaseStore` (`.sync/base.json`) + `status` real
- ✅ **Paso 8**: `PushCommand` v1 + `RemoteTransfer` (staging content-addressed + posix-rename) + integración con lock + heartbeat
- ✅ **Paso 9**: `PullCommand` v1 (download a tmp + verificar sha256 + ATOMIC_MOVE + conflictos como `.remote`)
- ✅ **Paso 10**: `LockHeartbeat` (scheduled refresh cada ttl/3) + `RemoteLockManager.acquireOrSteal` (CAS sobre lock huérfano vía `lock.new + posix-rename`, Apéndice D)
- ✅ **Paso 11**: `IgnoreMatcher` real con parser de `.gitignore` (comentarios, negaciones, anclaje, `**`, `?`, escapes). Lectura automática de `.gitignore` si `useGitignore=true`.
- ✅ **Paso 12**: `ResolveCommand` con `--keep-local | --keep-remote | --keep-both`. Ancla `base.json` correctamente al hash remoto para que el siguiente diff vea el resultado esperado (toUpload o unchanged según la estrategia).
- ✅ **Paso 13**: `WatchCommand` + `WatchState` + `StateStore`. Dos loops (scan local + poll remoto) sobre virtual threads. `--once` para un ciclo único. `status` lee `state.json` si está fresco (< 2× pollInterval).
- ✅ **Paso 14**: hardening. `--gc` en push limpia `staging/` huérfanos. Re-hash pre-upload (mitigación 3.6). `PathValidation` rechaza nombres Windows-inválidos (reserved CON/PRN/..., trailing space/dot, chars prohibidos, MAX_PATH=260).
- ⏭️ **Paso 15 (cuando publiques)**: tag `v0.1.0` → CI dispara release con binarios para los 3 OS. No requiere código.

## Layout

```
src/main/java/io/github/shizuka/sftpsync/
├── Main.java                    Entry point picocli
├── cli/                         InitCommand, StatusCommand, PushCommand, PullCommand,
│                                WatchCommand, ResolveCommand, ScanCommand (hidden, debug),
│                                PingCommand (hidden, debug)
├── config/                      SyncConfig, RemoteConfig, WatchConfig (todos records),
│                                SyncConfigStore (load/save .sync/config.json)
├── manifest/                    Manifest, ManifestEntry, ManifestStore, BaseStore (.sync/base.json),
│                                ScanCache, ScanCacheEntry, ScanCacheFile, ScanCacheStore,
│                                ManifestBuilder
├── diff/                        ChangeSet (sets por categoría), ThreeWayDiffer
├── sftp/                        SftpSession (wrapper sshj con HostKeyMode STRICT/INSECURE),
│                                RemoteManifestStore (load/save manifest remoto vía SFTP),
│                                RemoteLockManager + LockInfo + LockHeldException + LockHeartbeat
│                                (acquire/release/steal CAS + scheduled refresh),
│                                RemoteTransfer (upload to staging, promote, download, delete, gcStaging)
├── watcher/                     WatchState (snapshot del estado), StateStore (.sync/state.json)
└── util/                        Hashing (SHA-256 streaming), IgnoreMatcher (parser .gitignore),
                                  PathExpansion, PathValidation (compatibilidad Windows), Hostname

src/main/resources/META-INF/native-image/io.github.shizuka/sftp-sync/
├── reflect-config.json          POJOs/records que jackson-jr lee/escribe
└── resource-config.json         sshj.properties + META-INF/services/*
```

Los subcomandos `init/status/push/pull/watch/resolve` están registrados en `Main`.
`scan`, `ping`, `remote-manifest` y `lock` son `hidden = true` en sus `@Command` —
no aparecen en `--help` pero son ejecutables (`sftp-sync scan`,
`sftp-sync ping --insecure`, `sftp-sync remote-manifest [--put] --insecure`,
`sftp-sync lock [--acquire <op>|--release] --insecure`).

## Convenciones del proyecto

**Idioma**: el usuario habla español, prefiere conversación en español. JavaDoc y
comentarios también en español. Identificadores y nombres de tipos en inglés
estándar Java.

**Records sobre POJOs**: todos los DTOs/value-types son records. Defaults se
manejan en el compact constructor (típicamente "si vino 0, asumir missing y usar
default sensato"). Footgun documentado: pasar 0 explícitamente a un campo donde 0
es un valor válido te lo coerce al default.

**Tests**: JUnit 5 + AssertJ. `@DisplayName` en todos los tests con descripción
en inglés. Naming: `methodName_scenario_expectedBehavior()`.

**I/O atómica**: cualquier write a archivo importante usa `tmp + ATOMIC_MOVE`.
Patrón establecido en `SyncConfigStore.save`, repetido en `ManifestStore` y
`ScanCacheStore`.

**Picocli + sandbox testing**: comandos que producen output usan
`@Spec CommandSpec spec` y `spec.commandLine().getOut()/getErr()` — NO
`System.out.println` directo. Sin esto, los tests con `setOut(StringWriter)` no
capturan output. (`InitCommand` todavía usa System.out — pendiente de refactor,
no urgente porque sus tests no asertan stdout.)

**Cross-platform paths**: el manifest usa forward-slash siempre. La conversión
está en `ManifestBuilder.toRelative` (`replace('\\', '/')`).

## Caveats conocidos importantes

### 1. Por qué Apache MINA SSHD en vez de sshj

Migramos de sshj 0.40 a Apache MINA SSHD 2.16.0 porque sshj tenía
[#871](https://github.com/hierynomus/sshj/issues/871): crea una nueva instancia
de `BouncyCastleProvider` con `Class.forName().newInstance()` en runtime, y
JCE en native-image rechaza esa instancia recién creada. **MINA usa el provider
pre-registrado vía `SecurityUtils.isBouncyCastleRegistered()` — sin re-crear
la instancia, así que funciona.**

### 2. Patrón BouncyCastle + native-image (validado con MINA SSHD)

Receta validada (basada en `chirontt/jgit.pgm.native`):

1. **`BouncyCastleInitializer`** — clase normal con static block que llama
   `Security.addProvider(new BouncyCastleProvider())`.

2. **`--initialize-at-build-time`** para `org.bouncycastle`, `org.apache.sshd`,
   `org.slf4j` y la clase del initializer. Inicializa todo en build time;
   el provider queda snapshot-eado en el image heap.

3. **`-H:ClassInitialization=...:rerun`** para las clases con `SecureRandom`
   o DRBG en su `<clinit>`. El modo `rerun` significa "ya inicializado en
   build-time, pero RE-inicializar también en run-time" — resuelve el conflicto
   de seeds congeladas:
   - `org.apache.sshd.common.random.JceRandom`
   - `org.apache.sshd.common.random.JceRandom$Cache`
   - `org.bouncycastle.crypto.CryptoServicesRegistrar`
   - `org.bouncycastle.jcajce.provider.drbg.DRBG`, `$Default`, `$NonceAndIV`

4. **`proxy-config.json`** — MINA usa `Proxy.newProxyInstance` para event
   listeners. Hay que registrar las interfaces de proxy (SessionListener,
   ChannelListener, etc.) en `META-INF/native-image/.../proxy-config.json`.

5. **`reflect-config.json`** — agregar overloads de `getInstance(String, String)`
   para JCE classes (`KeyFactory`, `KeyAgreement`, `Cipher`, `Mac`, `Signature`,
   `KeyPairGenerator`, `MessageDigest`) ya que MINA los busca via reflection.

### 3. posix-rename via extensión OpenSSH (no CopyMode flag)

MINA SSHD rechaza `SftpClient.rename(src, dst, CopyMode.Overwrite)` cuando
el server habla SFTPv3 (lo que OpenSSH negocia por default). Los rename flags
solo están definidos en SFTPv5+. Para sobrescribir atómicamente en SFTPv3,
hay que invocar la extensión OpenSSH explícitamente:

```java
OpenSSHPosixRenameExtension ext = sftp.getExtension(OpenSSHPosixRenameExtension.class);
if (ext != null && ext.isSupported()) {
    ext.posixRename(src, dst);
}
```

Patrón encapsulado en `sftp/PosixRename.overwrite(sftp, src, dst)` con
fallback no-atómico (`remove + rename`) para servers que no anuncian la extensión.

### Optimizaciones de binario nativo

El binario producido por el perfil `native` aplica:

- `-march=compatibility`: target x86-64 baseline (no native CPU). Permite que
  el binario corra en cualquier máquina x86-64, no solo en la que compiló.
- `-H:IncludeLocales=en,es`: solo incluye los locales necesarios. Ahorra ~1-2 MB.
- `--no-fallback`: build falla si necesita modo fallback en lugar de hacerlo
  silenciosamente.

Post-build, el CI aplica **UPX `--best --lzma` solo en Linux** que comprime
el ELF (46 MB → ~11 MB con MINA+BC). En Windows, UPX rompe los binarios PE32+
generados por GraalVM Native Image (incompatibilidad de secciones SubstrateVM
— probado con UPX 4.2 y 5.1, distintos flags). Aceptamos el .exe en su tamaño
nativo. La asimetría de tamaño Linux/Windows queda documentada.

### 4. Passphrase en claves SSH NO soportada

`SftpSession.open()` solo acepta claves sin passphrase o autenticación por
password. Si la clave tiene passphrase, falla con `UserAuthException`. Documentado
en JavaDoc de `SftpSession`. Para v1.1 considerar integración con ssh-agent.

### 5. Password se guarda plain text en `config.json`

Cuando el usuario hace `init --password`, el password queda en
`.sync/config.json` sin cifrar. `init` imprime un warning recomendando
`chmod 600`. Para v1.1 considerar `passwordEnv` que lea de variable de entorno.

### 6. emberstack/sftp default mount

El usuario lo tiene corriendo en `127.0.0.1:22` con user `demo`/password `demo`,
y la carpeta remota mapeada a `/sftp` (no `/upload` como sugieren docs viejas).
Ajustar `remoteRoot` según docker-compose del usuario.

## Comandos típicos

```bash
# Build y test JVM (rápido, ~30s)
mvn test
mvn compile
mvn exec:java "-Dexec.args=--help"

# Build native (~30s–2min, requiere GraalVM JDK 25 + gcc + zlib1g-dev)
mvn -Pnative package
./target/sftp-sync --help

# Test contra emberstack local
mkdir -p /tmp/sftpsync-demo
./target/sftp-sync init --non-interactive \
  --host 127.0.0.1 --user demo --password demo --remote-root /sftp \
  -C /tmp/sftpsync-demo
./target/sftp-sync ping --insecure -C /tmp/sftpsync-demo
./target/sftp-sync scan --save -C /tmp/sftpsync-demo
cat /tmp/sftpsync-demo/.sync/manifest.json
```

## Repo y CI

- **Repo**: https://github.com/ShizukaJiku/sftp-sync (privado)
- **Autor commits**: Shizuka <shizuka.jiku@gmail.com>
- **CI**: `.github/workflows/ci.yml`
  - Job `jvm`: build + tests, corre en cada PR y push.
  - Job `native`: matrix `[ubuntu, windows, macos]`, solo en push a main + tags.
  - Job `release`: en tags `v*`, sube los 3 binarios como GitHub Release.

## Cuando arranques

Pregunta sugerida al usuario:
> "El proyecto sftp-sync está funcionalmente completo (pasos 1–14 del plan). Tenés
> push/pull con conflict handling, resolve con 3 estrategias, watch con state.json,
> heartbeat + steal de locks huérfanos, gitignore real, --gc del staging, y validación
> de paths Windows. Solo falta el paso 15 (release tag) que es trabajo de publicación
> sin código. ¿Querés que arranquemos por ahí, o hay algo más para pulir?"
