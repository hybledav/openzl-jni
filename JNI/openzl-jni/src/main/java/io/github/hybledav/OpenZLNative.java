package io.github.hybledav;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class OpenZLNative {
    private static final String LIBRARY_BASE_NAME = "openzl_jni";
    private static final String RESOURCE_TEMPLATE = "/lib/%s/%s/%s";
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    private OpenZLNative() {}

    static void load() {
        if (LOADED.get()) {
            return;
        }
        synchronized (OpenZLNative.class) {
            if (LOADED.get()) {
                return;
            }
            String os = normaliseOs(System.getProperty("os.name"));
            String arch = normaliseArch(System.getProperty("os.arch"));
            String mapped = System.mapLibraryName(LIBRARY_BASE_NAME);
            String classifierArch = toClassifierArch(arch);
            String classifier = os + "_" + classifierArch;
            String[] candidatePaths = {
                    String.format(Locale.ROOT, "/lib/%s/%s", classifier, mapped),
                    String.format(Locale.ROOT, RESOURCE_TEMPLATE, os, arch, mapped),
                    String.format(Locale.ROOT, RESOURCE_TEMPLATE, os, classifierArch, mapped)};

            IOException extractionError = null;
            for (String resourcePath : candidatePaths) {
                try (InputStream in = OpenZLNative.class.getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        Path temp = Files.createTempFile(LIBRARY_BASE_NAME + "-", mapped);
                        Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                        temp.toFile().deleteOnExit();
                        System.load(temp.toAbsolutePath().toString());
                        LOADED.set(true);
                        return;
                    }
                } catch (IOException ioe) {
                    extractionError = ioe;
                }
            }

            if (extractionError != null) {
                throw new UnsatisfiedLinkError("Failed to extract OpenZL native library: " + extractionError.getMessage());
            }

            try {
                System.loadLibrary(LIBRARY_BASE_NAME);
                LOADED.set(true);
                return;
            } catch (UnsatisfiedLinkError ule) {
                String message = String.format(Locale.ROOT,
                        "Unsupported platform for OpenZL JNI: %s/%s (resources %s, %s, %s not found)%n%s",
                        os,
                        arch,
                        candidatePaths[0],
                        candidatePaths[1],
                        candidatePaths[2],
                        ule.getMessage());
                throw new UnsatisfiedLinkError(message);
            }
        }
    }

    private static String normaliseOs(String name) {
        String lowered = Objects.toString(name, "unknown").toLowerCase(Locale.ROOT);
        if (lowered.contains("linux")) {
            return "linux";
        }
        if (lowered.contains("mac") || lowered.contains("darwin") || lowered.contains("os x")) {
            return "osx";
        }
        if (lowered.contains("win")) {
            return "windows";
        }
        return lowered.replaceAll("[^a-z0-9]+", "_");
    }

    private static String normaliseArch(String arch) {
        String lowered = Objects.toString(arch, "unknown").toLowerCase(Locale.ROOT);
        if (lowered.matches("^(x86_64|amd64)$")) {
            return "x86_64";
        }
        if (lowered.matches("^(x86|i386|i486|i586|i686)$")) {
            return "x86";
        }
        if (lowered.contains("aarch64") || lowered.contains("arm64")) {
            return "aarch64";
        }
        if (lowered.startsWith("arm")) {
            return "arm";
        }
        return lowered.replaceAll("[^a-z0-9]+", "_");
    }

    private static String toClassifierArch(String arch) {
        if ("x86_64".equals(arch)) {
            return "amd64";
        }
        return arch;
    }
}
