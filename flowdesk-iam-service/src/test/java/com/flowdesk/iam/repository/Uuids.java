package com.flowdesk.iam.repository;

import java.util.UUID;

/**
 * 在 UUID 与数据库二进制 UUID 之间转换，仅供 IAM 数据库测试夹具使用；生产 Mapper 使用 UUID TypeHandler。
 */
public final class Uuids {
    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private Uuids() {
    }

    /**
     * 将 UUID 高低位转换为固定 16 字节表示，与生产数据库标识的二进制存储保持一致。
     */
    public static byte[] toBytes(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new byte[]{
                (byte) (most >>> 56), (byte) (most >>> 48), (byte) (most >>> 40), (byte) (most >>> 32),
                (byte) (most >>> 24), (byte) (most >>> 16), (byte) (most >>> 8), (byte) most,
                (byte) (least >>> 56), (byte) (least >>> 48), (byte) (least >>> 40), (byte) (least >>> 32),
                (byte) (least >>> 24), (byte) (least >>> 16), (byte) (least >>> 8), (byte) least};
    }

    /**
     * 将固定 16 字节按相同顺序还原为 UUID，便于测试数据库读写进行身份比较。
     */
    public static UUID toUuid(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
