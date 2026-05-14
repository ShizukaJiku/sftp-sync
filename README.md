# sftp-sync

Sincronizador de carpetas multi-PC sobre SFTP, estilo Git.

Mantiene una carpeta sincronizada entre N máquinas usando un servidor SFTP como
fuente de verdad central. Modelo conceptual: comandos explícitos `push` / `pull`,
three-way diff con detección de conflictos manuales, y un proceso `watch` que
**solo observa** y reporta desactualizaciones sin aplicarlas automáticamente.

## Estado

Pre-MVP. Esqueleto del CLI funcional (todos los comandos imprimen `not yet implemented`).
Plan de implementación y diseño técnico completo en [`docs/design.md`](docs/design.md).

## Stack

- **GraalVM JDK 25** + Maven 3.9
- [sshj](https://github.com/hierynomus/sshj) 0.40.0 — SFTP cliente
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

## Build

Durante desarrollo, usar la JVM (build rápido):

```sh
mvn compile
mvn exec:java -Dexec.args="--help"
mvn test
```

Para producir el binario nativo (más lento, ~30s–2min):

```sh
mvn -Pnative package
./target/sftp-sync --help
```

Requiere GraalVM JDK 25 instalado (recomendado vía
[SDKMAN](https://sdkman.io/): `sdk install java 25-graalce`).

## Instalación

Binarios nativos para Linux y Windows. Linux ~11 MB (UPX), Windows ~45 MB.

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

### Scoop (Windows, planeado)

Próximamente: `scoop install sftp-sync` vía bucket dedicado. Mientras tanto,
usá los métodos de arriba.

### Construcción desde source

Para producir el binario localmente, ver la sección **Build** más abajo.

## Servidor SFTP

Probado contra [emberstack/sftp](https://hub.docker.com/r/emberstack/sftp) v5.1.71
en Docker. Cualquier servidor OpenSSH funciona porque dependemos de la
extensión estándar `posix-rename@openssh.com`.

## Licencia

[MIT](./LICENSE)
