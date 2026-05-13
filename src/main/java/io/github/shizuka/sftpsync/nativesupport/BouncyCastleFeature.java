package io.github.shizuka.sftpsync.nativesupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.graalvm.nativeimage.hosted.Feature;

import java.security.Security;

/**
 * GraalVM native-image {@link Feature} que registra BouncyCastle como JCE
 * provider <b>en build time</b>.
 *
 * <p>Activado vía {@code --features=...BouncyCastleFeature} en el perfil Maven
 * {@code native}. Solo se ejecuta durante el build del binario nativo; en JVM
 * normal esta clase nunca se carga (no hay referencias runtime a ella).
 *
 * <p>Por qué es necesario: native-image requiere que los providers JCE estén
 * registrados Y verificados en build time, no runtime. Sin esto, la primera
 * llamada a {@code KeyAgreement.getInstance("X25519", "BC")} truena con
 * {@code UnsupportedFeatureError}, y sshj propaga el error como NPE en
 * {@code initCipherFactories} (issue hierynomus/sshj#782).
 *
 * <p>Esta clase NO va al jar runtime — la dep {@code org.graalvm.sdk:nativeimage}
 * tiene scope {@code provided}, así que el compile la ve pero el package no.
 */
public final class BouncyCastleFeature implements Feature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Override
    public String getDescription() {
        return "Registers BouncyCastle as a JCE provider at native-image build time";
    }
}
