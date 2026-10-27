package com.flowdesk.common.security;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 从内联配置或 Spring 资源位置读取 PEM 密钥材料。
 */
public final class PemKeyMaterial {

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private PemKeyMaterial() {
    }

    /**
     * 优先使用内联 PEM，否则按 classpath 或文件路径以 UTF-8 读取；未配置返回空串，资源缺失或读取失败阻止启动。
     */
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
