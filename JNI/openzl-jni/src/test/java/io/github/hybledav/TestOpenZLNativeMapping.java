package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class TestOpenZLNativeMapping {

    private static String invokeNormaliseArch(String value) {
        try {
            Method method = OpenZLNative.class.getDeclaredMethod("normaliseArch", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, value);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Failed to invoke normaliseArch", e);
        }
    }

    private static String invokeToClassifierArch(String value) {
        try {
            Method method = OpenZLNative.class.getDeclaredMethod("toClassifierArch", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, value);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Failed to invoke toClassifierArch", e);
        }
    }

    @Test
    void x64AliasMapsToX86_64() {
        assertEquals("x86_64", invokeNormaliseArch("x64"), "x64 should map to canonical x86_64");
    }

    @Test
    void x64AliasProducesAmd64Classifier() {
        assertEquals("amd64", invokeToClassifierArch("x64"), "x64 should reuse amd64 classifier to match packaged resources");
    }
}
