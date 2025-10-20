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

    @Test
    void normaliseOsHandlesCommonVariants() throws Exception {
        assertEquals("linux", invokeNormaliseOs("Linux"));
        assertEquals("macos", invokeNormaliseOs("Mac OS X"));
        assertEquals("windows", invokeNormaliseOs("Windows 11"));
        assertEquals("custom_os", invokeNormaliseOs("Custom-OS"));
    }

    private static String invokeNormaliseOs(String value) throws Exception {
        Method method = OpenZLNative.class.getDeclaredMethod("normaliseOs", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    @Test
    void normaliseArchCoversAdditionalFamilies() {
        assertEquals("x86", invokeNormaliseArch("i686"));
        assertEquals("aarch64", invokeNormaliseArch("ARM64"));
        assertEquals("arm", invokeNormaliseArch("armv7"));
        assertEquals("weird_cpu", invokeNormaliseArch("Weird CPU"));
    }

    @Test
    void classifierFallbackUsesCanonicalValue() {
        assertEquals("arm64", invokeToClassifierArch("arm64"));
        assertEquals("mips", invokeToClassifierArch("mips"));
    }

    private static String[] invokeBuildCandidatePaths(String os, String arch, String classifierArch, String mapped) {
        try {
            Method method = OpenZLNative.class.getDeclaredMethod(
                    "buildCandidatePaths", String.class, String.class, String.class, String.class);
            method.setAccessible(true);
            return (String[]) method.invoke(null, os, arch, classifierArch, mapped);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Failed to invoke buildCandidatePaths", e);
        }
    }

    @Test
    void macOsProfilesIncludeOsxAlias() {
        String[] paths = invokeBuildCandidatePaths("macos", "x86_64", "amd64", "libopenzl_jni.dylib");
        assertTrue(java.util.Arrays.asList(paths).contains("/lib/macos_x86_64/libopenzl_jni.dylib"));
        assertTrue(java.util.Arrays.asList(paths).contains("/lib/osx_x86_64/libopenzl_jni.dylib"));
    }
}
