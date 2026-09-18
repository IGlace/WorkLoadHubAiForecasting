package com.workloadhub.forecastweb.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Demo only. The module encrypts the stored GitHub tokens with {@code whf.token-key}, a base64 32-byte key, and
 * without one no token can be saved or read. Production reads it from its secret store; this host, when
 * {@code WHF_TOKEN_KEY} is blank, reads or creates one in {@code forecast-web.token-key-file} so a stored token
 * survives a restart. Registered in {@code META-INF/spring.factories}.
 */
public final class TokenKeyFile implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String key = environment.getProperty("whf.token-key", "");
        String file = environment.getProperty("forecast-web.token-key-file", "");
        if (!key.isBlank() || file.isBlank()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource("forecast-web-token-key", Map.of("whf.token-key", readOrCreate(Path.of(file)))));
    }

    /** The key in the file, or a fresh one created there with mode 0600 where the file system has modes. */
    public static String readOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                String key = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!key.isBlank()) {
                    return key;
                }
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String key = Base64.getEncoder().encodeToString(bytes);
            Path parent = file.toAbsolutePath().getParent();
            // The permissions come first, on creation: writing the key and then narrowing the mode leaves it
            // world-readable for as long as the two calls take, and for good if the second one never runs.
            if (parent != null) {
                createDirectory(parent, "rwx------");
            }
            try {
                Files.createFile(file, ownerOnly("rw-------"));
            } catch (UnsupportedOperationException e) {
                Files.createFile(file);   // a file system without POSIX modes (Windows)
            }
            Files.writeString(file, key + System.lineSeparator(), StandardCharsets.UTF_8);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read or create the token key file " + file, e);
        }
    }

    private static FileAttribute<?>[] ownerOnly(String mode) {
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))};
    }

    /** Creates the directory and every missing parent, the last one owner-only where the file system has modes. */
    private static void createDirectory(Path dir, String mode) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Path parent = dir.getParent();
        if (parent != null) {
            createDirectory(parent, mode);
        }
        try {
            Files.createDirectory(dir, ownerOnly(mode));
        } catch (UnsupportedOperationException e) {
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException e) {
            // another process won the race; its directory is good enough
        }
    }
}
