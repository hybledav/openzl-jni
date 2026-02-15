package io.github.hybledav;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TestNativeSymbolParity {

    @Test
    public void headerContainsOpenZLCompressorNativeMethods() throws Exception {
        String header = Files.readString(Path.of("..", "OpenZLCompressor.h"));

        Method[] nativeMethods = Arrays.stream(OpenZLCompressor.class.getDeclaredMethods())
                .filter(m -> Modifier.isNative(m.getModifiers()))
                .toArray(Method[]::new);

        for (Method method : nativeMethods) {
            String symbol = "Java_io_github_hybledav_OpenZLCompressor_" + method.getName();
            assertTrue(
                    header.contains(symbol),
                    () -> "Missing JNI header declaration for native method: "
                            + method.getName() + " (expected token: " + symbol + ")");
        }
    }

    @Test
    public void trainNativeSymbolIsCallable() throws Exception {
        OpenZLNative.load();

        Method trainNative = OpenZLCompressor.class.getDeclaredMethod(
                "trainNative",
                String.class,
                byte[][].class,
                int.class,
                int.class,
                int.class,
                boolean.class);
        trainNative.setAccessible(true);

        byte[][] inputs = new byte[][] {
                "a".getBytes(),
                "b".getBytes(),
                "c".getBytes()
        };

        Object result;
        try {
            result = trainNative.invoke(null, "serial", inputs, 1, 1, 0, false);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof UnsatisfiedLinkError) {
                fail("trainNative symbol is missing or not linked: " + cause.getMessage());
            }
            throw ex;
        }

        assertNotNull(result);
        assertTrue(((byte[][]) result).length >= 1);
    }

    @Test
    public void destroyCompressorHandleNativeSymbolIsCallable() throws Exception {
        OpenZLNative.load();

        Method destroyByHandle = OpenZLCompressor.class
                .getDeclaredMethod("destroyCompressorHandleNative", long.class);
        destroyByHandle.setAccessible(true);

        assertDoesNotThrow(() -> {
            try {
                destroyByHandle.invoke(null, 0L);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof UnsatisfiedLinkError) {
                    fail("destroyCompressorHandleNative symbol is missing: " + cause.getMessage());
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new RuntimeException(cause);
            }
        });
    }
}
