package com.flowdesk.iam.service;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 采用 PBKDF2-HMAC-SHA256 保存加盐密码；编码包含算法、迭代次数、盐与摘要。
 * 校验使用恒定时间比较；存储格式非法或无法验证时拒绝认证。
 */
@Component
public final class PasswordHashService {

    public static final String SCHEME = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final byte[] TIMING_DUMMY_HASH = new PasswordHashService()
            .encode("flowdesk-timing-dummy-password");

    /**
     * 为密码生成独立随机盐并执行 PBKDF2 派生，将方案、迭代次数、盐和摘要编码为 UTF-8 字节。
     * 相同密码每次调用产生不同存储值，验证时从存储值恢复派生参数。
     */
    public byte[] encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = derive(rawPassword, salt, ITERATIONS, HASH_BYTES);
        String encoded = SCHEME + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 对固定虚拟凭证执行完整 PBKDF2 派生；无账户候选时仍承担近似真实错密的计算成本，
     * 降低通过响应耗时推断用户名是否存在的可能。
     */
    public void consumeTimingDummy(String rawPassword) {
        matches(rawPassword, TIMING_DUMMY_HASH);
    }

    /**
     * 解析已保存的密码格式并重算摘要，以恒定时间比较确认密码。
     * 空输入、未知方案、损坏编码和超限派生参数均返回 false，避免异常凭证绕过认证或占用过量计算资源。
     */
    public boolean matches(String rawPassword, byte[] stored) {
        if (rawPassword == null || stored == null || stored.length == 0) {
            return false;
        }
        String encoded = new String(stored, StandardCharsets.UTF_8);
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            // 校验派生参数上限，避免损坏的存储值触发不受控的计算量。
            if (iterations <= 0 || iterations > 10_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length == 0 || salt.length > 64 || expected.length == 0 || expected.length > 128) {
                return false;
            }
            byte[] actual = derive(rawPassword, salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        } catch (IllegalStateException e) {
            // 存储凭证异常或无法验证时直接拒绝认证。
            return false;
        }
    }

    /** 使用给定盐、迭代次数和输出字节数执行 PBKDF2；密码算法不可用时抛出服务端异常。 */
    private static byte[] derive(String rawPassword, byte[] salt, int iterations, int hashBytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, hashBytes * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password derivation failed", e);
        }
    }
}
