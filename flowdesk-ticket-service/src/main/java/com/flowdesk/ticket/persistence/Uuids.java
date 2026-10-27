package com.flowdesk.ticket.persistence;
import java.util.UUID;
/**
 * 数据库二进制 UUID 与 Java UUID 转换工具，统一字节顺序和空值约定。
 */
public final class Uuids {
    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private Uuids() {
    }

    /**
     * 将 UUID 转为数据库二进制表示，并沿用转换方法的空值约定。
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
     * 将数据库二进制标识恢复为 UUID，供业务层使用。
     */
    public static UUID toUuid(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
