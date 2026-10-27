package com.flowdesk.ticket.sla;

import com.flowdesk.common.notification.NotificationWireCodec;

import java.util.HexFormat;
import java.time.Instant;
import java.util.UUID;

/** Redis 键与成员的规范编码，UUID 使用原始 16 字节对应的小写十六进制。 */
final class SlaDueIndexCodec {
    private static final HexFormat HEX = HexFormat.of();

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private SlaDueIndexCodec() {
    }

    /**
     * 将租户 UUID 编码为稳定键片段，供 Redis 租户命名空间使用。
     */
    static String tenantHex(UUID tenantId) {
        return HEX.formatHex(NotificationWireCodec.uuidBytes(tenantId));
    }

    /**
     * 构造租户索引键，使相关 Redis 键共享租户哈希槽。
     */
    static String key(UUID tenantId) {
        return "flowdesk:ticket:sla:due:v1:{" + tenantHex(tenantId) + "}";
    }

    /**
     * 构造含租户和重建身份的临时键，隔离重建与正式索引。
     */
    static String rebuildKey(UUID runId, UUID tenantId) {
        if (runId == null || tenantId == null) {
            throw new IllegalArgumentException("SLA rebuild identifiers must not be null");
        }
        // 临时键与正式键使用相同哈希槽，使 Redis Cluster 下的栅栏 Lua 替换可原子执行。
        return "flowdesk:ticket:sla:due:rebuild:" + runId + ":{" + tenantHex(tenantId) + "}";
    }

    /**
     * 编码时钟与阶段为规范索引成员，供扫描时安全解析。
     */
    static String member(UUID tenantId, UUID clockId, String stage) {
        if (tenantId == null || clockId == null) {
            throw new IllegalArgumentException("SLA index identifiers must not be null");
        }
        if (!"REMINDER".equals(stage) && !"BREACH".equals(stage)) {
            throw new IllegalArgumentException("Unsupported SLA index stage");
        }
        return tenantHex(tenantId) + ":" + tenantHex(clockId) + ":" + stage;
    }

    /** 将 UTC 纪元微秒向下取整为 Redis 使用的毫秒候选分值。 */
    static long score(Instant dueAt) {
        if (dueAt == null) throw new IllegalArgumentException("SLA dueAt must not be null");
        long micros = Math.addExact(Math.multiplyExact(dueAt.getEpochSecond(), 1_000_000L),
                dueAt.getNano() / 1_000L);
        return Math.floorDiv(micros, 1_000L);
    }

    /**
     * 解析规范输入并核验结构，拒绝无法恢复为契约字段的内容。
     */
    static SlaDueIndex.Candidate parse(String member, double score) {
        if (member == null) {
            throw new IllegalArgumentException("SLA index member is null");
        }
        String[] parts = member.split(":", -1);
        if (parts.length != 3 || parts[0].length() != 32 || parts[1].length() != 32
                || !("REMINDER".equals(parts[2]) || "BREACH".equals(parts[2]))) {
            throw new IllegalArgumentException("Malformed SLA index member");
        }
        return new SlaDueIndex.Candidate(uuid(parts[0]), uuid(parts[1]), parts[2], score);
    }

    /**
     * 转换业务标识与规范 UUID 字节表示，遵循既有空值与格式校验约定。
     */
    private static UUID uuid(String value) {
        byte[] bytes = HEX.parseHex(value);
        if (bytes.length != 16) {
            throw new IllegalArgumentException("Malformed UUID member");
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
