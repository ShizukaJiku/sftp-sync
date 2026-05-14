# sftp-sync

Sincronizador de carpetas multi-PC sobre SFTP, estilo Git.

Mantiene una carpeta sincronizada entre N máquinas usando un servidor SFTP como
fuente de verdad central. Modelo conceptual: comandos explícitos `push` / `pull`,
three-way diff con detección de conflictos manuales, y un proceso `watch` que
**solo observa** y reporta desactualizaciones sin aplicarlas automáticamente.

## Estado

v0.1 — sync engine completo. Push, pull, watch, resolve y manejo de conflictos
funcionales contra OpenSSH y emberstack/sftp. Plan de implementación y diseño
técnico en [`docs/design.md`](docs/design.md).

## Stack

- **GraalVM JDK 25** community + Maven 3.9
- [Apache MINA SSHD](https://github.com/apache/mina-sshd) 2.16 — cliente SFTP
- [BouncyCastle](https://www.bouncycastle.org/) 1.80 — JCE provider para curve25519 / Ed25519
- [picocli](https://picocli.info/) 4.7.7 — CLI parsing
- [jackson-jr](https://github.com/FasterXML/jackson-jr) 2.21.3 — JSON (GraalVM-friendly)
- slf4j-simple 2.0 — logging mínimo
- JUnit 5 + AssertJ — tests

## Comandos

```text
sftp-sync init     Inicializar la carpeta actual conectándola a un remoto SFTP
sftp-sync status   Mostrar qué cambió localmente, remotamente, y conflictos pendientes
sftp-sync push     Subir cambios locales al remoto. Aborta si hay conflictos
sftp-sync pull     Bajar cambios remotos. Conflictos se marcan, no se aplican
sftp-sync watch    Vigilar local y remoto, mantener el status fresco. No sincroniza
sftp-sync resolve  Resolver un conflicto post-pull eligiendo qué versión queda
```

Opciones notables:

- `init --remote-parent /sftp` toma el nombre de la carpeta actual y arma el
  `remoteRoot` automáticamente (ej. cwd `proj-x/` + parent `/sftp` →
  `remoteRoot=/sftp/proj-x`). Mutex con `--remote-root` clásico.
- `pull --workers N` (default `8`, max `32`) descarga archivos en paralelo. Cada
  worker usa su propia sesión SFTP; el speedup más grande aparece con muchos
  archivos chicos.
- Compresión SSH (`zlib@openssh.com`/`zlib`) se negocia automáticamente con el
  server. Para workloads YAML/código/JSON puede reducir bytes en el wire 3-5×.
  Si el server no la soporta, degrada limpio a `none`.

## Filtros (`.syncignore`)

Qué se sincroniza y qué no lo decide la combinación de dos fuentes:

1. **Defaults built-in** en `.sync/config.json` campo `ignore`:
   `.sync/`, `.git/`, `target/`, `build/`, `node_modules/`, `.idea/`, `.vscode/`,
   `*.class`, `*.log`. Editables si querés.
2. **`.syncignore`** en la raíz del proyecto. Sintaxis idéntica a `.gitignore`
   (comments con `#`, negación con `!`, anclaje con `/`, globs `*`/`?`/`**`).
   `init` bootstrappea uno con ejemplos comunes; lo editás según tu proyecto.

> **`.syncignore` NO es `.gitignore`.** Lo que excluís de git no necesariamente
> coincide con lo que querés excluir del sync — típico: un `lib/*.jar` que git
> ignora pero que SÍ querés compartir entre PCs. sftp-sync **no lee
> `.gitignore`** deliberadamente.

Ejemplo de `.syncignore` para des-ignorar un jar:

```gitignore
# Patrones extra propios del proyecto
*.swp

# Des-ignorar un jar que algún default filtraría
!lib/important.jar
```

## Instalación

Binarios nativos para Linux y Windows. Linux ~8 MB (UPX), Windows ~33 MB.

> **Requisito de CPU**: el binario está compilado con `-march=x86-64-v3`. Requiere
> **CPU Intel Haswell (2013+) o AMD Excavator (2015+)**. Cualquier desktop/laptop
> de los últimos 10 años cumple. CPUs Atom muy antiguas o AMD pre-2015 no pueden
> ejecutarlo (fallan con SIGILL).

### Desde GitHub Releases (recomendado)

Última versión: [releases page](https://github.com/ShizukaJiku/sftp-sync/releases/latest).

**Linux** (terminal):

```bash
curl -L -o /usr/local/bin/sftp-sync \
  https://github.com/ShizukaJiku/sftp-sync/releases/latest/download/sftp-sync-linux-x64.bin
chmod +x /usr/local/bin/sftp-sync
```

**Windows** (PowerShell, admin):

```powershell
$dest = "$env:LOCALAPPDATA\Programs\sftp-sync"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Invoke-WebRequest `
  -Uri "https://github.com/ShizukaJiku/sftp-sync/releases/latest/download/sftp-sync-windows-x64.exe" `
  -OutFile "$dest\sftp-sync.exe"
[Environment]::SetEnvironmentVariable("Path", "$env:Path;$dest", "User")
```

Reabrí la terminal y `sftp-sync --help` debería funcionar.

### Con `gh` CLI

Si ya tenés [`gh`](https://cli.github.com/) instalado:

```bash
gh release download --repo ShizukaJiku/sftp-sync \
  -p 'sftp-sync-linux-x64.bin'   # o 'sftp-sync-windows-x64.exe'
```

### Artefactos de cada push a `main`/`development`

Si querés un build de una rama específica (no una release tageada), bajá desde
**Actions → último run → Artifacts**:

- `sftp-sync-linux-x64`
- `sftp-sync-windows-x64`

### Scoop (Windows)

```powershell
scoop bucket add shizuka https://github.com/ShizukaJiku/scoop-bucket
scoop install sftp-sync
```

`scoop update sftp-sync` te trae la última release automáticamente (el manifest
tiene `checkver`/`autoupdate` apuntando a las GitHub Releases).

### Construcción desde source

Para producir el binario localmente, ver la sección **Build** más abajo.

## Build

Durante desarrollo, usar la JVM (build rápido):

```sh
mvn compile
mvn exec:java -Dexec.args="--help"
mvn test
```

Para producir el binario nativo (~1-3 min, segunda corrida es más rápida por cache):

```sh
mvn -Pnative -DskipTests package
./target/sftp-sync --help
```

### Requisitos

- **GraalVM JDK 25 community** o **Oracle GraalVM JDK 25**. Vía
  [SDKMAN](https://sdkman.io/): `sdk install java 25-graalce`. En Windows, bajar
  el zip de [oracle.com/graalvm](https://www.oracle.com/java/technologies/downloads/#graalvm)
  o usar [scoop](https://scoop.sh/): `scoop install graalvm-jdk-25-lts`.
- **Linux**: `gcc` y `zlib1g-dev` (en Debian/Ubuntu: `apt install build-essential zlib1g-dev`).
- **Windows**: Visual Studio Build Tools 2022+ con el workload **"Desktop development with C++"**
  (incluye MSVC y Windows SDK). Si tenés VS Community / Professional, instalá el mismo workload
  desde el VS Installer. GraalVM Native Image llama al `link.exe` de MSVC para producir el `.exe`.
- **macOS**: Xcode Command Line Tools (`xcode-select --install`).

El `JAVA_HOME` debe apuntar a la instalación de GraalVM, y `native-image`
debe estar en el `PATH` (típicamente en `$JAVA_HOME/bin`).

## Servidor SFTP

Probado contra [emberstack/sftp](https://hub.docker.com/r/emberstack/sftp) v5.1.71
en Docker y OpenSSH stock. Requisito: el server debe anunciar la extensión
estándar `posix-rename@openssh.com` (todos los OpenSSH modernos la soportan).

## Licencia

[MIT](./LICENSE)
