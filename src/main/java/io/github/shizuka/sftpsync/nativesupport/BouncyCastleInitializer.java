package io.github.shizuka.sftpsync.nativesupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

/**
 * Registra {@link BouncyCastleProvider} como JCE provider.
 *
 * <p>En native-image, esta clase está marcada con
 * {@code --initialize-at-build-time=...BouncyCastleInitializer}: su
 * {@code <clinit>} corre en build time y el objeto del provider queda
 * snapshot-eado en el image heap. Luego, las clases BC con SecureRandom o DRBG
 * en su {@code <clinit>} se vuelven a inicializar en run-time vía el flag
 * {@code -H:ClassInitialization=...:rerun} (ver perfil {@code native} del pom).
 *
 * <p>MINA SSHD detecta automáticamente BC vía
 * {@code SecurityUtils.isBouncyCastleRegistered} sin re-crear la instancia
 * (a diferencia de sshj 0.40 — ver issue hierynomus/sshj#871).
 */
public final class BouncyCastleInitializer {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private BouncyCastleInitializer() {}

    /** No-op para forzar la carga de la clase desde código alcanzable. */
    public static void ensureInitialized() {
        // intencionalmente vacío
    }
}
