package com.flowdesk.gateway.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Serves the built Vue shell from the Gateway's same origin. API paths are not
 * handled here; they remain exclusively owned by the route table.
 */
@Controller
public final class SpaController {

    private static final String INDEX_PATH = "static/index.html";
    private static final String ASSET_PREFIX = "static/assets/";

    @GetMapping(value = {
            "/", "/login",
            "/tickets", "/tickets/**",
            "/approvals", "/approvals/**",
            "/alerts", "/alerts/**",
            "/notifications", "/settings/users", "/settings/catalog"
    }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> shell() {
        Resource index = new ClassPathResource(INDEX_PATH);
        if (!index.exists()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache())
                .body(index);
    }

    /**
     * Vite emits flat hashed files under assets/. Restricting the variable to a
     * single safe path segment avoids turning this endpoint into a classpath
     * traversal primitive.
     */
    @GetMapping(value = "/assets/{assetName:.+}")
    public ResponseEntity<Resource> asset(@PathVariable String assetName) {
        if (assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new ClassPathResource(ASSET_PREFIX + assetName);
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(assetMediaType(assetName))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(resource);
    }

    private static MediaType assetMediaType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) return MediaType.valueOf("text/css");
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        if (lower.endsWith(".js")) return MediaType.valueOf("text/javascript");
        if (lower.endsWith(".map")) return MediaType.APPLICATION_JSON;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
