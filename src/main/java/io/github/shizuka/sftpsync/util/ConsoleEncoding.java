package io.github.shizuka.sftpsync.util;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Configura la consola Windows para que renderice UTF-8 correctamente.
 *
 * <p>En Windows, PowerShell/cmd por default usan code page CP437 (es-ES) o
 * CP1252 según locale. Java escribe UTF-8 al stdout (forzado en
 * {@code Main.main} vía {@code setOut/setErr}), pero la consola interpreta los
 * bytes como su code page nativo y produce mojibake: {@code á} (UTF-8 {@code C3 A1})
 * se ve como {@code ├í}, {@code é} como {@code ├®}, etc.
 *
 * <p>El fix global es decirle a la consola que use UTF-8 (CP 65001). Win32
 * expone {@code SetConsoleOutputCP} y {@code SetConsoleCP} en {@code kernel32.dll}.
 * Las llamamos vía la Foreign Function and Memory API (estable desde Java 22),
 * que también funciona en GraalVM Native Image.
 *
 * <p>En Linux y macOS la operación es no-op — esas terminales ya son UTF-8 por
 * default desde hace décadas.
 */
public final class ConsoleEncoding {

    /** Identificador Win32 del code page UTF-8. */
    private static final int CP_UTF8 = 65001;

    private ConsoleEncoding() {}

    /**
     * Intenta forzar la consola a UTF-8. Best-effort: si la operación falla
     * (no es Windows, no hay consola adjunta, FFM no disponible, etc.), retorna
     * silenciosamente. El caller NO debe asumir éxito — los streams stdout/stderr
     * de Java siguen escribiendo UTF-8, pero el render depende del code page de
     * la consola en última instancia.
     */
    public static void enableUtf8OnWindows() {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase().contains("windows")) {
            return;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            MethodHandle setOutputCp = linker.downcallHandle(
                kernel32.find("SetConsoleOutputCP").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            MethodHandle setInputCp = linker.downcallHandle(
                kernel32.find("SetConsoleCP").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            setOutputCp.invoke(CP_UTF8);
            setInputCp.invoke(CP_UTF8);
        } catch (Throwable _) {
            // Best-effort. Si no podemos cambiar el code page (ej. la app corre
            // sin consola adjunta o sin permisos), igual seguimos — el output
            // saldrá en UTF-8 puro y el destino que sea lo interpretará como
            // mejor pueda.
        }
    }
}
