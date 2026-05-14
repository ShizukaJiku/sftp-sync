package io.github.shizuka.sftpsync.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests de {@link ConsoleEncoding}. Limitado a smoke tests porque verificar
 * realmente el effect del SetConsoleOutputCP requeriría un Win32 capture del
 * code page activo, lo cual es out-of-scope para JUnit.
 */
class ConsoleEncodingTest {

    @Test
    @DisplayName("enableUtf8OnWindows nunca tira, sin importar el OS")
    void enableUtf8OnWindows_anyPlatform_doesNotThrow() {
        // El método es best-effort. Tira excepciones internas las captura
        // y retorna en silencio. Verificación trivial pero asegura que el
        // código compile y los handles de FFM se resuelvan en build de
        // GraalVM Native Image (donde la verificación es más estricta).
        assertThatCode(ConsoleEncoding::enableUtf8OnWindows).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enableUtf8OnWindows es idempotente: llamarlo dos veces no rompe")
    void enableUtf8OnWindows_calledTwice_doesNotThrow() {
        ConsoleEncoding.enableUtf8OnWindows();
        assertThatCode(ConsoleEncoding::enableUtf8OnWindows).doesNotThrowAnyException();
    }
}
