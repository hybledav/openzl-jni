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

        String[] candidatePaths = buildCandidatePaths(os, arch, classifierArch, mapped);

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
                        "Unsupported platform for OpenZL JNI: %s/%s (checked %s)%n%s",
                        os,
                        arch,
                        String.join(", ", candidatePaths),
                        ule.getMessage());
                throw new UnsatisfiedLinkError(message);
            }
        }
    }

    private static String[] buildCandidatePaths(String initialOs, String arch, String classifierArch, String mapped) {
        java.util.LinkedHashSet<String> osCandidates = new java.util.LinkedHashSet<>();
        osCandidates.add(initialOs);
        if ("macos".equals(initialOs)) {
            osCandidates.add("osx");
        } else if ("osx".equals(initialOs)) {
            osCandidates.add("macos");
        }

        java.util.LinkedHashSet<String> archCandidates = new java.util.LinkedHashSet<>();
        archCandidates.add(arch);
        archCandidates.add(classifierArch);

        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        for (String osCandidate : osCandidates) {
            for (String archCandidate : archCandidates) {
                paths.add(String.format(Locale.ROOT, "/lib/%s_%s/%s", osCandidate, archCandidate, mapped));
            }
        }
        for (String osCandidate : osCandidates) {
            for (String archCandidate : archCandidates) {
                paths.add(String.format(Locale.ROOT, RESOURCE_TEMPLATE, osCandidate, archCandidate, mapped));
            }
        }

        return paths.toArray(new String[0]);
    }
    private static String normaliseOs(String name) {
        String lowered = Objects.toString(name, "unknown").toLowerCase(Locale.ROOT);
        if (lowered.contains("linux")) {
            return "linux";
        }
        if (lowered.contains("mac") || lowered.contains("darwin") || lowered.contains("os x") || lowered.contains("osx")) {
            return "macos";
        }
        if (lowered.contains("win")) {
            return "windows";
        }
        return lowered.replaceAll("[^a-z0-9]+", "_");
    }

    private static String normaliseArch(String arch) {
        String lowered = Objects.toString(arch, "unknown").toLowerCase(Locale.ROOT);
        if (lowered.matches("^(x86_64|amd64|x64)$")) {
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
        String canonical = normaliseArch(arch);
        if ("x86_64".equals(canonical)) {
            return "amd64";
        }
        if ("aarch64".equals(canonical)) {
            return "arm64";
        }
        return canonical;
    }
}
