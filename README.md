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

## Binarios precompilados

Cada push a `main` (y cada tag `vX.Y.Z`) dispara el CI, que produce binarios
nativos para los tres OS. Para descargarlos:

1. Andá a la pestaña **Actions** del repo.
2. Abrí el último run del workflow `CI`.
3. Bajá la sección **Artifacts** y descargá:
   - `sftp-sync-linux-x64`
   - `sftp-sync-windows-x64`
   - `sftp-sync-macos-arm64`

En tags `vX.Y.Z` además se publica un GitHub Release con los tres binarios
adjuntos.

## Servidor SFTP

Probado contra [emberstack/sftp](https://hub.docker.com/r/emberstack/sftp) v5.1.71
en Docker. Cualquier servidor OpenSSH funciona porque dependemos de la
extensión estándar `posix-rename@openssh.com`.

## Licencia

[MIT](./LICENSE)
