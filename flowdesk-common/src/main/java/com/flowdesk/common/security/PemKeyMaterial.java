package com.flowdesk.common.security;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Resolves PEM key material from either inline config or a Spring resource location. */
public final class PemKeyMaterial {

    private PemKeyMaterial() {
    }

    public static String resolve(String inline,
                                 String location,
                                 ResourceLoader resourceLoader) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        if (location == null || location.isBlank()) {
            return "";
        }
        Resource resource = location.startsWith("classpath:") || location.startsWith("file:")
                ? resourceLoader.getResource(location)
                : new FileSystemResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("JWT key resource does not exist: " + location);
        }
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read JWT key resource: " + location, e);
        }
    }
}
