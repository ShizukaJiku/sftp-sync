# sftp-sync — Diseño técnico

**Estado:** Propuesto
**Versión:** 1.0
**Autor:** Shizuka
**Fecha:** 2026-05-13

---

## 1. Resumen ejecutivo

Aplicación Java de línea de comandos que sincroniza una carpeta entre múltiples PCs usando un servidor SFTP (emberstack/sftp en Docker) como fuente de verdad central. Modelo conceptual estilo Git: comandos explícitos `push` / `pull`, three-way diff con detección de conflictos manuales, y un proceso `watch` que **solo observa** el estado remoto para reportar desactualizaciones sin aplicarlas automáticamente.

Tamaños objetivo: 10–120 MB, cientos de archivos, 2–5 PCs cliente.

---

## 2. Requisitos

### 2.1 Funcionales

- **Inicialización**: enganchar una carpeta local a un remoto SFTP y emitir credenciales/config.
- **Status**: reportar qué cambió localmente, qué cambió en el remoto, y qué archivos están en conflicto, sin tocar nada.
- **Pull**: descargar cambios remotos al disco local. Conflictos se marcan, no se aplican.
- **Push**: subir cambios locales al remoto. Aborta si el remoto cambió desde el último sync (compare-and-swap por manifest).
- **Watch**: proceso de fondo que mantiene el reporte de `status` fresco sin que el usuario corra el comando.
- **Ignore**: respetar patrones `.gitignore` y patrones extra del usuario.
- **Resolución manual de conflictos**: dejar artefactos `archivo.local-<host>` + `archivo.remote` y comando `resolve`.

### 2.2 No funcionales

| Atributo | Objetivo |
|----------|----------|
| Latencia `status` (sin red) | < 50 ms |
| Latencia `status` (con red, cold) | < 2 s |
| Throughput `push` / `pull` | Limitado por red, no por CPU (SHA-256 stream ≥ 200 MB/s) |
| Memoria proceso | < 200 MB para carpetas de 120 MB con miles de archivos |
| Pérdida de datos por crash | **Cero** sobre el remoto. Cero sobre archivos locales no modificados. |
| Concurrencia simultánea | 5 clientes pusheando en ventanas solapadas, ninguno corrompe el remoto. |
| Cross-platform | Windows, Linux, macOS clientes. Server Linux (Docker). |

### 2.3 Restricciones

- **No** corre nada custom en el servidor SFTP. Todo el protocolo es archivos sobre SFTP estándar.
- **GraalVM JDK 25** (LTS hasta 2033, edición community). Aprovechamos Structured Concurrency y Scoped Values, ambos finalizados en 25. Más importante: nos da `native-image` desde el día 1.
- **Distribución como binario nativo desde el inicio** (GraalVM `native-image`). MVP: build local para el OS del autor. Post-MVP: CI multi-OS con GitHub Actions matriz.
- **Build: Maven 3.9.x con `pom.xml`**. Estándar, explícito, suficiente para single-module.
- Código GraalVM-friendly: cero reflection dinámica de usuario; librerías elegidas con foco en compat con AOT compilation.

---

## 3. Verificación de factibilidad

Cada decisión técnica del diseño se apoya en una capacidad concreta de la stack. Verificadas:

### 3.1 Lock atómico vía SFTP

**Claim:** Crear un archivo "si no existe" de forma atómica usando SFTP estándar.

**Verificación:** SFTP protocolo v3 (RFC draft-ietf-secsh-filexfer-02), packet `SSH_FXP_OPEN` con `pflags = SSH_FXF_CREAT | SSH_FXF_EXCL` (0x08 | 0x20). El servidor responde con `SSH_FX_FAILURE` si el archivo ya existe. OpenSSH (base de emberstack) implementa esto correctamente. sshj expone vía:

```java
sftp.open("/path/lock", EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL));
// throws SFTPException con StatusCode.FAILURE si existe
```

**Estado:** ✅ Factible.

### 3.2 Rename atómico (overwrite)

**Claim:** Mover un archivo en el remoto sobre uno existente de forma atómica.

**Verificación:** OpenSSH define la extensión `posix-rename@openssh.com` que invoca `rename(2)` POSIX, atómico en ext4/overlay2 (FS típico de Docker). sshj 0.38.0+ tiene fallback automático al detectar la extensión anunciada por el server:

```java
sftp.rename(src, dst);  // usa posix-rename si está disponible
```

Si no estuviera (no es el caso de emberstack), caeríamos al `SSH_FXP_RENAME` estándar que **no** está definido para overwrite — habría que `unlink + rename`, dejando ventana de carrera. Pero como confirmamos OpenSSH Linux, no aplica.

**⚠️ Caveat documentado:** `posix-rename@openssh.com` **no es atómico en Win32-OpenSSH** (issue conocido en PowerShell/Win32-OpenSSH). Si se moviera el server SFTP a un Windows nativo en el futuro, la garantía de atomicidad desaparece. Mitigación: mantener el server en Linux/Docker.

**Estado:** ✅ Factible con sshj 0.40.0 contra emberstack/sftp en Docker.

### 3.3 Detección de cambios en filesystem local

**Claim:** Notificación en tiempo real de cambios en la carpeta local en Windows/Linux/Mac.

**Verificación:** `java.nio.file.WatchService` se apoya en backends nativos: `ReadDirectoryChangesW` (Windows), `inotify` (Linux), `FSEvents` (macOS desde JDK 22) o kqueue (macOS hasta JDK 21). Limitación: **no es recursivo**. Hay que registrar cada subdirectorio manualmente y manejar `ENTRY_CREATE` sobre directorios para registrar los nuevos. Patrón estándar y bien documentado.

**Estado:** ✅ Factible con cuidado en el manejo recursivo.

### 3.4 Conexión SSH persistente

**Claim:** El proceso `watch` mantiene una conexión SSH abierta por horas para reusar handshakes.

**Verificación:** sshj soporta keepalive vía `SSHClient.getConnection().getKeepAlive().setKeepAliveInterval(30)`. El timeout típico de OpenSSH (`ClientAliveInterval`) es configurable; emberstack lo deja en defaults razonables. Reconexión con backoff exponencial en caso de drop es trivial.

**Estado:** ✅ Factible.

### 3.5 Hash + transferencia en streaming

**Claim:** SHA-256 de archivos de 120 MB sin cargarlos a memoria.

**Verificación:** `MessageDigest` + `DigestInputStream` con buffer de 64 KB. Performance medida típica ≥ 250 MB/s en CPU moderna. 120 MB ≈ 0.5 s de hash. Cero presión de memoria.

**Estado:** ✅ Factible.

### 3.6 Riesgo identificado: hash inestable por escritura concurrente

**Problema:** Si el usuario edita un archivo **mientras** lo estamos hasheando para push, el hash que calculamos no coincide con el contenido finalmente subido.

**Mitigación:** Re-hashear cada archivo inmediatamente antes de abrir el stream de upload. Si el hash difiere del de la fase de scan → archivo cambió durante el sync → abortar el archivo con warning, dejar para el próximo push. Ventana de carrera residual (entre re-hash y upload) es del orden de microsegundos: tolerable.

**Estado:** ⚠️ Aceptable con la mitigación. Documentado como limitación conocida.

---

## 4. Arquitectura de alto nivel

### 4.1 Topología

```
   ┌──────────────────┐        ┌──────────────────┐
   │   PC #1 (Win)    │        │   PC #2 (Linux)  │
   │                  │        │                  │
   │  sftp-sync CLI   │        │  sftp-sync CLI   │
   │       +          │        │       +          │
   │  sftp-sync watch │        │  sftp-sync watch │
   └────────┬─────────┘        └────────┬─────────┘
            │ SFTP (SSH)                │ SFTP (SSH)
            └──────────────┬────────────┘
                           │
                  ┌────────▼─────────┐
                  │   emberstack/    │
                  │   sftp (Docker)  │
                  │                  │
                  │  /upload/        │
                  │   ├── .sync/     │  ← manifest, lock, staging
                  │   └── <archivos> │
                  └──────────────────┘
```

### 4.2 Componentes (proceso cliente)

```
┌─────────────────────────────────────────────────────────────┐
│                       sftp-sync (JVM)                       │
├─────────────────────────────────────────────────────────────┤
│  cli/        InitCmd, StatusCmd, PushCmd, PullCmd,          │
│              WatchCmd, ResolveCmd                           │
├─────────────────────────────────────────────────────────────┤
│  manifest/   ManifestBuilder (scan local + scancache)       │
│              ManifestSerializer (Jackson)                   │
├─────────────────────────────────────────────────────────────┤
│  diff/       ThreeWayDiffer  →  ChangeSet                   │
│                                  ├─ toPush                  │
│                                  ├─ toPull                  │
│                                  └─ conflicts               │
├─────────────────────────────────────────────────────────────┤
│  sftp/       SftpSession   (singleton, sshj wrap)           │
│              RemoteLockManager  (acquire/heartbeat/release) │
│              RemoteTransfer (staging + atomic rename)       │
│              RemoteManifestStore (load/save manifest)       │
├─────────────────────────────────────────────────────────────┤
│  watcher/    LocalWatcher (WatchService recursivo)          │
│              RemotePoller (lee remote manifest cada N s)    │
│              StateCache (escribe state.json)                │
├─────────────────────────────────────────────────────────────┤
│  util/       Hashing, IgnoreMatcher, PathNormalizer         │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. Modelo de datos

Todo el estado vive en archivos JSON. Sin DB. Sin esquemas binarios. Auditable.

### 5.1 `.sync/config.json` (local)

```json
{
  "clientId": "9f8a1c2e-...-uuid",
  "remote": {
    "host": "192.168.1.50",
    "port": 22,
    "user": "syncuser",
    "keyPath": "~/.ssh/sftp_sync_key",
    "remoteRoot": "/upload/proyecto-x"
  },
  "ignore": [
    "target/",
    "*.class",
    ".idea/",
    ".sync/"
  ],
  "useGitignore": true,
  "maxFileSizeMB": 200,
  "watch": {
    "pollIntervalSeconds": 30,
    "debounceMs": 500
  }
}
```

### 5.2 `.sync/manifest.json` (local **y** remoto, mismo schema)

```json
{
  "version": 1,
  "generatedAt": "2026-05-13T14:22:01Z",
  "generatedBy": "<clientId>",
  "entries": {
    "src/Main.java": {
      "sha256": "a3f...",
      "size": 1842
    },
    "README.md": {
      "sha256": "b71...",
      "size": 304
    }
  }
}
```

**Nota:** sin `mtime`. La identidad del archivo es `(sha256, size)`. Esto evita problemas de precisión cross-FS.

### 5.3 `.sync/base.json` (local)

Mismo schema que `manifest.json`. Representa **el estado del remoto en el último sync exitoso** (último push o último pull completo). Es el ancla del three-way diff. Se actualiza al final de push o pull exitoso.

### 5.4 `.sync/scancache.json` (local, no viaja)

Cache para no rehashear todo en cada scan:

```json
{
  "src/Main.java": { "mtime": 1715615200123, "size": 1842, "sha256": "a3f..." }
}
```

Algoritmo en `ManifestBuilder`:
1. Listar archivos del filesystem.
2. Para cada uno: si `scancache` tiene una entrada con `mtime` y `size` idénticos → reusar `sha256`. Sino → rehashear y actualizar cache.
3. Borrar entradas del cache que ya no existen en el FS.

### 5.5 `.sync/state.json` (local, escrito por watcher)

```json
{
  "lastRemoteCheckAt": "2026-05-13T14:21:30Z",
  "lastLocalScanAt": "2026-05-13T14:21:55Z",
  "summary": {
    "localChanged": 3,
    "remoteChanged": 1,
    "conflicts": 0
  },
  "remoteReachable": true,
  "errors": []
}
```

`sync status` simplemente imprime esto (rápido). Si está vacío o stale → corre un scan ad-hoc.

### 5.6 `.sync/lock` (remoto)

```json
{
  "holder": "PC-WIN-shizuka+pid27814+e2a8...",
  "operation": "push",
  "acquiredAt": "2026-05-13T14:20:00Z",
  "lastHeartbeatAt": "2026-05-13T14:21:30Z",
  "ttlSeconds": 300
}
```

Lock expira si `now - lastHeartbeatAt > ttlSeconds`. Durante un push largo, un hilo de fondo reescribe el archivo con `lastHeartbeatAt` fresco cada `ttl / 3` segundos.

### 5.7 `.sync/staging/` (remoto)

Directorio donde aterrizan archivos en tránsito durante un push. Nombre = `<sha256>` (content-addressed). Al final del push, cada uno se renombra atómicamente a su ubicación final. Si quedan huérfanos (crash) → un próximo `sync push --gc` los limpia.

---

## 6. Flujos principales

### 6.1 `sync init`

```
1. Prompt al usuario: host, puerto, user, keyPath, remoteRoot.
2. Generar clientId (UUID).
3. Conectar al SFTP. Si falla → abortar con mensaje claro.
4. Verificar (o crear) <remoteRoot>/.sync/.
5. Si <remoteRoot>/.sync/manifest.json existe (otro cliente ya inicializó):
    a. Confirmar al usuario: "se va a hacer pull inicial".
    b. Bajar manifest y todos los archivos.
6. Si no existe (primera vez):
    a. Generar manifest local desde la carpeta.
    b. Push inicial completo (todos los archivos a staging + rename + manifest).
7. Escribir .sync/config.json y .sync/base.json.
```

### 6.2 `sync status`

```
1. Si .sync/state.json es fresco (< pollIntervalSeconds * 2) → imprimirlo.
2. Sino:
    a. Scan local (con scancache) → manifest_local.
    b. Conectar SFTP, bajar .sync/manifest.json → manifest_remote.
    c. Cargar .sync/base.json → manifest_base.
    d. ThreeWayDiffer(base, local, remote) → ChangeSet.
    e. Imprimir resumen.
```

### 6.3 `sync push`

```
1. Scan local → manifest_local.
2. Conectar SFTP.
3. Adquirir lock:
    a. Intentar OPEN(.sync/lock, CREAT|EXCL|WRITE).
    b. Si falla → leer el lock existente.
       - Si "lastHeartbeatAt + ttl < now" → lock huérfano:
           - Borrar el lock (con cuidado: leer holder primero, intentar borrar; si otro cliente lo refresca entremedio, retry).
           - Volver al paso (a).
       - Si está vivo → abortar: "otro cliente está sincronizando".
4. Iniciar hilo de heartbeat (rescribe lock con lastHeartbeatAt fresco cada ttl/3).
5. Bajar .sync/manifest.json → manifest_remote.
6. Cargar .sync/base.json → manifest_base.
7. ThreeWayDiffer(base, local, remote) → ChangeSet.
8. Si hay conflictos → liberar lock, reportar, NO subir nada.
9. Si manifest_remote != manifest_base → otro cliente pusheó desde nuestro último sync.
    - Si los archivos remotos cambiados NO se superponen con nuestros locales → es seguro, integramos.
    - Si se superponen sin conflicto (mismo contenido) → ok.
    - Si se superponen con contenido distinto → conflicto, abortar.
10. Para cada archivo en toPush:
    a. Re-hashear local (mitigación 3.6). Si difiere → skip + warning.
    b. Subir a .sync/staging/<sha256> (resume si ya existe con mismo hash).
11. Cuando todos los uploads están en staging:
    a. Para cada uno: posix-rename desde staging/<sha256> a su path final.
    b. Aplicar deletes remotos.
12. Subir manifest nuevo (a .sync/manifest.json.tmp, luego rename atómico).
13. Liberar lock (borrar .sync/lock).
14. Actualizar .sync/base.json local con el manifest nuevo.
15. Reportar.
```

**Garantía:** si crashea en cualquier punto antes del paso 12, el remoto sigue viendo el manifest viejo. Los archivos en staging son invisibles para otros clientes. El próximo `--gc` los limpia.

### 6.4 `sync pull`

```
1. Scan local → manifest_local.
2. Bajar .sync/manifest.json → manifest_remote.
3. Cargar .sync/base.json → manifest_base.
4. ThreeWayDiffer → ChangeSet.
5. Si hay conflictos:
    - Para cada uno: mantener local como está, descargar versión remota a "<path>.remote".
    - Reportar y NO actualizar base.json (próximo status seguirá viendo conflicto).
6. Para cada archivo en toPull (no-conflicto):
    a. Descargar a "<path>.tmp".
    b. Verificar sha256 contra manifest_remote. Si difiere → abortar ese archivo.
    c. Files.move(tmp, final, ATOMIC_MOVE).
7. Aplicar deletes locales (con confirmación si la carpeta tiene >N archivos a borrar).
8. Actualizar .sync/base.json = manifest_remote (solo si no quedaron conflictos pendientes).
9. Reportar.
```

### 6.5 `sync watch`

```
Proceso largo, dos loops en threads separados (virtual threads):

Loop A — vigilancia local:
  - WatchService registrado recursivamente.
  - Eventos CREATE / MODIFY / DELETE → debounce 500 ms → rehash incremental.
  - Actualiza manifest_local en memoria y state.json.

Loop B — vigilancia remota:
  - Cada pollIntervalSeconds, sobre la conexión SSH persistente:
    - Bajar solo .sync/manifest.json (es pequeño).
    - Si su sha256 cambió desde el último poll → recalcular ChangeSet.
    - Actualizar state.json.

Shutdown limpio: SIGINT/SIGTERM → cerrar SFTP, drenar threads.
```

### 6.6 `sync resolve <path>`

```
Tras un pull con conflicto, existe <path> (local) y <path>.remote.
Opciones:
  --keep-local   → borrar <path>.remote, actualizar base.json (sha local).
  --keep-remote  → mover <path>.remote → <path>, actualizar base.json (sha remote).
  --keep-both    → renombrar <path> → <path>.local-<host>, dejar ambos como archivos nuevos a pushear.
```

---

## 7. Three-way diff: tabla de verdad completa

`B` = base (último sync). `L` = local actual. `R` = remoto actual. `=` o `≠` por hash.

| B | L | R | L vs B | R vs B | L vs R | Acción push | Acción pull |
|---|---|---|--------|--------|--------|-------------|-------------|
| x | x | x | =      | =      | =      | skip        | skip        |
| x | y | x | ≠      | =      | ≠      | upload      | skip        |
| x | x | y | =      | ≠      | ≠      | skip        | download    |
| x | y | y | ≠      | ≠      | =      | skip (ya está) | skip (ya está) |
| x | y | z | ≠      | ≠      | ≠      | **conflicto** | **conflicto** |
| x | — | x | borrado| =      | —      | delete remoto | recrear local (si pull) |
| x | x | — | =      | borrado| —      | recrear remoto (si push) | delete local |
| x | — | — | borrado| borrado| —      | skip (ambos coinciden) | skip |
| x | y | — | borrado→y | borrado | — | **conflicto** | **conflicto** |
| x | — | y | borrado | borrado→y | — | **conflicto** | **conflicto** |
| — | x | — | nuevo  | —      | —      | upload      | skip        |
| — | — | x | —      | nuevo  | —      | skip        | download    |
| — | x | x | nuevo  | nuevo  | =      | skip (ya está) | skip |
| — | x | y | nuevo  | nuevo  | ≠      | **conflicto** | **conflicto** |

"—" = el archivo no existe en ese snapshot.

---

## 8. Manejo de errores

Cada operación falla de manera explícita y recuperable. Tabla:

| Fallo | Detección | Recuperación |
|-------|-----------|--------------|
| Servidor SFTP inalcanzable | timeout TCP/SSH | Push/pull abortan limpio. Watch entra a modo "remoteReachable: false" y reintenta con backoff. |
| Auth falla (clave inválida) | `UserAuthException` de sshj | Mensaje claro, salida con código 2. |
| Lock vivo de otro cliente | `OPEN(EXCL)` falla, lock leído OK, heartbeat reciente | Abortar push con mensaje. Esperar y reintentar manualmente. |
| Lock huérfano | heartbeat expirado | Robo de lock con verificación post-CAS. |
| Crash en medio de push (antes del paso 12) | N/A | Idempotente: nuevo push reusará archivos ya en staging (mismo content hash). Manifest remoto intacto. |
| Crash en medio de pull | `.tmp` files quedan en disco | Próximo pull: re-descargar. Limpieza de `.tmp` huérfanos al inicio de cada pull. |
| Archivo local cambia durante hash → upload | re-hash antes de open stream difiere | Skip + warning, archivo se reintenta en próximo push. |
| Hash bajado no coincide con manifest | comparación post-download | Abortar el archivo, intentar de nuevo. Si 3 fallos → reportar corrupción. |
| `.sync/manifest.json` corrupto en remoto | JSON parse error | Abortar todas las ops. Sugerir restaurar desde `.sync/manifest.json.bak` (mantener última versión válida en backup). |
| Path inválido en Windows (>260 chars, reserved name) | validación en deserialización de manifest | Skip archivo + warning. |
| Disco lleno (local) durante pull | IOException en write | Borrar `.tmp` parcial, abortar pull, mensaje claro. |
| `.gitignore` malformado | parser tolerante | Ignorar línea problemática, warning, continuar. |
| Reloj local desincronizado | TTL del lock falla raro | Documentado. NTP recomendado. |

---

## 9. Estimaciones de carga

### Caso de uso esperado

- 3–5 PCs cliente.
- Carpeta 10–120 MB, 100–2000 archivos.
- ~10 push/día/PC, ~20 pull/día/PC.
- Watch: 1 conexión persistente por PC, poll cada 30 s.

### Tráfico estimado

| Métrica | Valor |
|---------|-------|
| Manifest.json típico | 100–500 KB |
| Watch poll cost | ~500 KB / 30 s = 17 KB/s sostenido por cliente |
| Watch total (5 clientes) | 85 KB/s sostenido — despreciable |
| Push completo de 120 MB | ~15 s a 8 MB/s (red local típica) |
| Operaciones SFTP por día | ~10⁴, incluyendo manifests |

### Footprint emberstack

- Disco: tamaño de la carpeta × 1.05 (overhead de manifest + staging transitorio).
- CPU: prácticamente cero (es un sshd con sftp-server). Una Pi sería suficiente.
- RAM: < 50 MB.

### Cliente

- Memoria: < 200 MB en watch mode (la mayoría es la JVM base).
- CPU: bursts cortos en hash; idle el resto del tiempo.

**Conclusión de capacity:** sobra. No requiere optimización para el caso target.

---

## 10. Trade-offs explícitos

| Decisión | Alternativa rechazada | Por qué |
|----------|----------------------|---------|
| SFTP "tonto" con archivos | Agente custom en server | Portabilidad. emberstack es plug-and-play. |
| JSON sin compresión | gzip o binario | Auditabilidad. Manifest cabe en KB. |
| Three-way diff | Diff por timestamps | Robusto cross-FS. Determinístico. |
| Lock con TTL + heartbeat | Lock simple con force-unlock | Operacionalmente sostenible. |
| Manifest sin mtime | Manifest con mtime | Precisión cross-FS inconsistente. |
| Content-addressed staging | Path-addressed staging | Permite resume y dedupe. |
| Watch solo observa | Watch sincroniza | Match con mental model del usuario. |
| Resolución manual de conflictos | Auto-merge | No queremos perder cambios silenciosamente. |
| sshj | JSch, Apache MINA SSHD | API más limpia, mantenida activamente, soporta extensiones OpenSSH. |
| picocli | jcommander, raw args | Generación automática de help, completion, subcomandos. **Plus:** `picocli-codegen` genera config de reflection para GraalVM automáticamente. |
| **Maven** | Gradle Kotlin DSL | Más estándar, XML explícito, suficiente para single-module. Equivalencia funcional para nuestro caso (incluyendo native-image). |
| **jackson-jr** | jackson-databind, Moshi, Gson | Subproyecto oficial de Jackson, GraalVM-friendly out-of-the-box, suficiente para JSONs simples de schema fijo. databind requeriría reflection-config manual. |
| **slf4j-simple** | Logback, log4j2 | Logback funciona con native-image pero requiere config extra. Para una CLI tool, simple a stderr alcanza. |
| **GraalVM native-image desde día 1** | shadowJar / jpackage | Decisión del usuario. Costo: build lento, builds por OS. Beneficio: startup ms, memoria baja, sin JRE en cliente. Mitigación de costo: dev loop usa JVM, CI hace native. |
| Sin GUI | Tray icon, daemon completo | YAGNI para el MVP. CLI cubre el flujo. |

---

## 11. Limitaciones conocidas (fuera de MVP)

1. **No preserva bit ejecutable ni permisos POSIX.** Archivos sincronizados quedan con permisos default del usuario destino.
2. **No sigue symlinks.** Se skipean con warning.
3. **No cifrado en reposo.** SFTP cifra en tránsito, pero los archivos quedan en claro en el server.
4. **No historial / rollback.** Cada push pisa el estado anterior.
5. **No delta-sync intra-archivo.** Cambios chicos en archivos grandes → re-upload completo.
6. **No resolución de conflictos asistida.** No abrimos un merge tool; solo dejamos los archivos.
7. **No resume de uploads cortados a media.** Re-empezamos. SFTP soporta append, no vale la complejidad para 120 MB.
8. **Reloj local debe estar razonablemente sincronizado.** Para que el TTL del lock no tenga sorpresas.
9. **Asumimos passphrase vacía en la clave SSH (o ssh-agent).** Pedir passphrase por stdin queda para v1.1.

---

## 12. Cosas a revisar cuando crezca

- **>10 clientes simultáneos**: el lock global se volverá cuello de botella. Considerar locks por subdirectorio o un protocolo CRDT-like.
- **Carpetas >5 GB**: re-uploads completos en archivos grandes duelen. Implementar delta-sync (BSDIFF, rsync-like) sobre SFTP.
- **Latencia alta (server remoto, no LAN)**: parelelizar transferencias con virtual threads + pool de canales SFTP.
- **Auditoría**: mantener `.sync/history/<timestamp>.json` con los últimos N manifests para diagnóstico.
- **Multi-tenant en mismo server**: namespacing por `remoteRoot` ya lo permite, pero faltarían quotas y auth por carpeta.

---

## 13. Próximos pasos (implementación)

Orden propuesto, cada paso ejecutable y verificable contra el container de emberstack:

1. Scaffold Maven + picocli. `mvn compile exec:java -Dexec.args="--help"` funciona. Verificar también que `mvn -Pnative package` produce un binario funcional.
2. `SyncConfig` + `init` (prompt + escritura del JSON).
3. `Hashing` + `ManifestBuilder` + `scancache`. Comando `sync scan` (debug-only) imprime manifest local.
4. `SftpSession` con sshj. Comando `sync ping` valida conexión.
5. `RemoteManifestStore`. Comando `sync remote-manifest` baja y muestra el manifest remoto.
6. `RemoteLockManager` (acquire + release, sin heartbeat aún).
7. `ThreeWayDiffer` + `ChangeSet`. Comando `sync status` imprime el diff sin tocar nada.
8. `PushCmd` v1: staging + posix-rename + manifest atómico + lock simple.
9. `PullCmd` v1: download con tmp + ATOMIC_MOVE + verificación de hash.
10. Heartbeat del lock + manejo de huérfanos.
11. `IgnoreMatcher` con soporte `.gitignore`.
12. `ResolveCmd` para conflictos.
13. `LocalWatcher` + `RemotePoller` + `state.json`. Comando `sync watch`.
14. Hardening: re-hash pre-upload, validaciones de path Windows, `--gc` de staging.
15. Empaquetado: shadowJar + script de launcher cross-platform.

Cada paso cierra con un test manual end-to-end contra el container y un par de tests unitarios para la lógica pura (diff, ignore, hashing).

---

## Apéndice A — Stack verificado y dependencias Maven

**Versiones verificadas a mayo 2026** (todas las versiones explícitamente confirmadas como vivas y compatibles):

| Componente | Versión | Notas |
|-----------|---------|-------|
| **GraalVM JDK** | 25 (community) | LTS hasta 2033. Structured Concurrency, Scoped Values, AOT cache. Trae `native-image`. |
| **Maven** | 3.9.x | Estándar. Soporta Java 25 con maven-compiler-plugin 3.13+. |
| sshj | 0.40.0 | posix-rename desde 0.38.0. Mantenido. Requiere reflection hints en native-image. |
| picocli | 4.7.7 | Integración GraalVM first-class. |
| picocli-codegen | 4.7.7 | Annotation processor: genera reflect-config.json automáticamente. |
| **jackson-jr-objects** | 2.21.3 | Subproyecto Jackson, ~200 KB, GraalVM-friendly. |
| **slf4j-simple** | 2.0.16 | Logging básico a stderr, zero-config con native-image. |
| JUnit | 5.11.4 | Estable. |
| AssertJ | 3.26.3 | Estable. |
| **native-maven-plugin** | 0.10.4 | Plugin oficial de GraalVM para Maven. |
| emberstack/sftp (Docker) | v5.1.71 | **Pinear, no usar `:latest`.** OpenSSH Linux base. |

**`pom.xml`:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>sftp-sync</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <picocli.version>4.7.7</picocli.version>
    <jackson.version>2.21.3</jackson.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.hierynomus</groupId>
      <artifactId>sshj</artifactId>
      <version>0.40.0</version>
    </dependency>
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>${picocli.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.jr</groupId>
      <artifactId>jackson-jr-objects</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>2.0.16</version>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>2.0.16</version>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.4</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <version>3.26.3</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>info.picocli</groupId>
              <artifactId>picocli-codegen</artifactId>
              <version>${picocli.version}</version>
            </path>
          </annotationProcessorPaths>
          <compilerArgs>
            <arg>-Aproject=${project.groupId}/${project.artifactId}</arg>
          </compilerArgs>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.5.0</version>
        <configuration>
          <mainClass>com.example.sftpsync.Main</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>

  <profiles>
    <profile>
      <id>native</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
            <version>0.10.4</version>
            <extensions>true</extensions>
            <executions>
              <execution>
                <id>build-native</id>
                <goals><goal>compile-no-fork</goal></goals>
                <phase>package</phase>
              </execution>
            </executions>
            <configuration>
              <imageName>sftp-sync</imageName>
              <mainClass>com.example.sftpsync.Main</mainClass>
              <buildArgs>
                <buildArg>--no-fallback</buildArg>
                <buildArg>-H:+ReportExceptionStackTraces</buildArg>
              </buildArgs>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
</project>
```

**Comandos:**

| Tarea | Comando |
|-------|---------|
| Dev loop (rápido, JVM) | `mvn compile exec:java -Dexec.args="status"` |
| Tests | `mvn test` |
| Fat-jar (debug, opcional) | `mvn package` |
| **Binario nativo** | `mvn -Pnative package` |
| Ejecutar binario | `./target/sftp-sync status` |

## Apéndice B — Reflection config para GraalVM

`picocli-codegen` (annotation processor) genera automáticamente:

- `META-INF/native-image/<group>/<artifact>/reflect-config.json`
- `META-INF/native-image/<group>/<artifact>/resource-config.json`
- `META-INF/native-image/<group>/<artifact>/proxy-config.json`

Esto cubre **todo lo de picocli y los DTOs anotados** sin trabajo manual.

Lo que sí hay que agregar a mano (en `src/main/resources/META-INF/native-image/...`):

1. **sshj + BouncyCastle**: ~20 líneas de JSON para registrar los providers crypto. Hay un template público de la comunidad sshj.
2. **jackson-jr**: prácticamente nada — usa solo getters/setters, sin reflection mágica. Si algún DTO no ronca, registrar con `@RegisterReflectionForBinding` (anotación standard del runtime).

**Estrategia para descubrir gaps:**
- Correr `native-image` con `-H:+PrintAnalysisCallTree` durante desarrollo.
- O usar el agente: `java -agentlib:native-image-agent=config-output-dir=...` mientras corremos los tests, captura todo y emite los configs.

## Apéndice C — Estrategia de empaquetado

**Decisión:** GraalVM native-image desde día 1.

| Aspecto | Plan |
|---------|------|
| Build local (MVP) | Solo el OS del autor. `mvn -Pnative package`. |
| Build CI multi-OS (post-MVP) | GitHub Actions con `strategy.matrix: [ubuntu-latest, windows-latest, macos-latest]`. ~50 líneas de YAML. Outputs como release artifacts en cada tag. |
| Dev loop | Siempre JVM (`mvn exec:java`), nunca native. Native es solo para release. |
| Fat-jar | Disponible como fallback (`mvn package` sin perfil), útil para debugging. |

**Por qué no shadowJar / jpackage:**
- shadowJar: requiere Java en el cliente, no aprovecha startup ms.
- jpackage: combina lo peor — no tan rápido como native, no tan simple como fat-jar, ~50 MB.

**Realidades de native-image documentadas para no sorprendernos:**
- Build time esperado: 30 s – 2 min. No bloquea el dev loop porque seguimos en JVM.
- El binario Linux no corre en Windows/macOS (ni viceversa).
- Reflection requiere config (cubierto en Apéndice B).
- `MethodHandles.Lookup` dinámico no funciona — lo evitamos por diseño.

## Apéndice D — Contrato del archivo `.sync/lock`

Si dos clientes ven el mismo lock vencido, ambos intentan robarlo. Para evitar dos pushes simultáneos tras robo:

```
1. Cliente A lee lock vencido (holder X, heartbeat=t0).
2. Cliente A intenta crear .sync/lock.new con EXCL.
3. Si OK → A escribe sus datos a lock.new, hace posix-rename lock.new → lock.
   - Si el rename gana la carrera contra otro cliente B haciendo lo mismo,
     A queda con el lock. B ve su rename fallar o el resultado final
     no coincide con sus datos → B reintenta verlo como "lock vivo".
4. Releer .sync/lock. Si holder != A → otro ganó, abortar.
```

Esto es CAS sobre SFTP: aprovecha que `posix-rename` es atómico y que solo uno gana.
