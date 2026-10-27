package com.flowdesk.ticket.sla;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 使用 Redis 有序集合保存 SLA 候选顺序，租约栅栏与原子脚本保护重建索引的替换。 */
public final class RedisSlaDueIndex implements SlaDueIndex {
    private static final String EMPTY_MARKER = "__empty__";
    private static final DefaultRedisScript<Long> ATOMIC_SWAP = new DefaultRedisScript<>("""
            local source = KEYS[1]
            local target = KEYS[2]
            local fence = KEYS[3]
            local marker = ARGV[1]
            local expected = ARGV[2]
            if expected ~= '' and redis.call('GET', fence) ~= expected then
              return 0
            end
            if redis.call('EXISTS', source) == 0 then
              redis.call('ZADD', source, '0', marker)
            end
            redis.call('DEL', target)
            redis.call('RENAME', source, target)
            redis.call('ZREM', target, marker)
            -- The temporary key is lease-bound.  Its TTL must never carry
            -- over to the formal key after a successful swap.
            redis.call('PERSIST', target)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INSTALL_FENCE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local epoch = tonumber(ARGV[1])
            local token = ARGV[2]
            local ttl = tonumber(ARGV[3])
            if epoch == nil or ttl == nil or ttl < 1 then
              return 0
            end
            local current = redis.call('GET', key)
            if current == false then
              redis.call('SET', key, token, 'PX', ttl)
              return 1
            end
            local separator = string.find(current, ':')
            local currentEpoch = tonumber(separator and string.sub(current, 1, separator - 1) or '-1')
            if currentEpoch == nil or epoch > currentEpoch then
              redis.call('SET', key, token, 'PX', ttl)
              return 1
            end
            if current == token then
              redis.call('PEXPIRE', key, ttl)
              return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_FENCE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
              return 0
            end
            local ttl = tonumber(ARGV[2])
            if ttl == nil or ttl < 1 then
              return 0
            end
            return redis.call('PEXPIRE', KEYS[1], ttl)
            """, Long.class);
    private static final DefaultRedisScript<Long> UPSERT_REBUILD = new DefaultRedisScript<>("""
            local ttl = tonumber(ARGV[3])
            if ttl == nil or ttl < 1 then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ttl)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE_REBUILD = new DefaultRedisScript<>("""
            local ttl = tonumber(ARGV[2])
            if ttl == nil or ttl < 1 then
              return 0
            end
            redis.call('ZREM', KEYS[1], ARGV[1])
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('PEXPIRE', KEYS[1], ttl)
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> TOUCH_REBUILD = new DefaultRedisScript<>("""
            local ttl = tonumber(ARGV[1])
            if ttl == nil or ttl < 1 then
              return 0
            end
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('PEXPIRE', KEYS[1], ttl)
            end
            -- A missing temporary key means the rebuild was externally
            -- discarded or expired; the owner must treat that as lease loss.
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> FENCED_UPSERT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FENCED_REMOVE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then
              return 0
            end
            redis.call('ZREM', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;

    /**
     * 构造SLA组件并保存调用依赖。
     */
    public RedisSlaDueIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 写入或刷新到期候选，使扫描器按时间定位 SLA 阶段。
     */
    @Override
    public void upsert(UUID tenantId, UUID slaClockId, String stage, Instant dueAt) {
        if (dueAt == null) {
            remove(tenantId, slaClockId, stage);
            return;
        }
        redis.opsForZSet().add(SlaDueIndexCodec.key(tenantId),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage),
                SlaDueIndexCodec.score(dueAt));
    }

    /**
     * 移除已失效候选，避免已结算或已触发阶段继续被扫描。
     */
    @Override
    public void remove(UUID tenantId, UUID slaClockId, String stage) {
        redis.opsForZSet().remove(SlaDueIndexCodec.key(tenantId),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage));
    }

    /**
     * 按租户、截止时间与数量上限读取到期候选，供后台有界处理。
     */
    @Override
    public List<Candidate> due(UUID tenantId, Instant now, int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("SLA Redis candidate limit must be 1..500");
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                .rangeByScoreWithScores(SlaDueIndexCodec.key(tenantId),
                        Double.NEGATIVE_INFINITY, SlaDueIndexCodec.score(now), 0, limit);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<Candidate> result = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            try {
                Candidate candidate = SlaDueIndexCodec.parse(tuple.getValue(), tuple.getScore());
                if (!tenantId.equals(candidate.tenantId())) {
                    continue;
                }
                result.add(candidate);
            }
            catch (IllegalArgumentException ignored) {
                // 忽略格式损坏的技术索引成员，仍由有界 MySQL 安全扫描提供权威候选。
            }
        }
        return List.copyOf(result);
    }

    /**
     * 清理指定租户索引，供后续从数据库事实重新建立。
     */
    @Override
    public void clear(UUID tenantId) {
        redis.delete(SlaDueIndexCodec.key(tenantId));
    }

    /**
     * 判断当前索引是否可用，供扫描器选择数据库降级路径。
     */
    @Override
    public boolean available() {
        try {
            return Boolean.TRUE.equals(redis.execute(
                    (RedisCallback<Boolean>) connection -> connection.ping() != null));
        }
        catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * 构造租户索引键，使相关 Redis 键共享租户哈希槽。
     */
    public String key(UUID tenantId) {
        return SlaDueIndexCodec.key(tenantId);
    }

    /**
     * 构造含租户和重建身份的临时键，隔离重建与正式索引。
     */
    public String rebuildKey(UUID runId, UUID tenantId) {
        return SlaDueIndexCodec.rebuildKey(runId, tenantId);
    }

    /** 最终原子替换使用的 Redis 侧栅栏键。 */
    public String fenceKey(UUID tenantId) {
        return "flowdesk:ticket:sla:fence:{" + SlaDueIndexCodec.tenantHex(tenantId) + "}";
    }

    /** 只有新 epoch 大于已存 epoch 时才安装栅栏，拒绝旧工作线程覆盖。 */
    public boolean installFence(UUID tenantId, UUID runId, long fencingEpoch, Duration ttl) {
        if (tenantId == null || runId == null || fencingEpoch < 1) {
            throw new IllegalArgumentException("Invalid SLA rebuild fence");
        }
        String token = fencingEpoch + ":" + runId;
        Long installed = redis.execute(INSTALL_FENCE, List.of(fenceKey(tenantId)),
                Long.toString(fencingEpoch), token, Long.toString(ttlMillis(ttl)));
        return Long.valueOf(1L).equals(installed);
    }

    /** 兼容未配置租约的小范围适配器调用。 */
    public boolean installFence(UUID tenantId, UUID runId, long fencingEpoch) {
        return installFence(tenantId, runId, fencingEpoch, Duration.ofSeconds(30));
    }

    /** 仅为完全匹配的栅栏持有者续期；栅栏丢失或出现更新版本均代表失去租约。 */
    public boolean renewFence(UUID tenantId, UUID runId, long fencingEpoch, Duration ttl) {
        if (tenantId == null || runId == null || fencingEpoch < 1) {
            throw new IllegalArgumentException("Invalid SLA rebuild fence");
        }
        Long renewed = redis.execute(RENEW_FENCE, List.of(fenceKey(tenantId)),
                fencingEpoch + ":" + runId, Long.toString(ttlMillis(ttl)));
        return Long.valueOf(1L).equals(renewed);
    }

    /**
     * 核对 Redis 围栏仍属本次重建者，阻止旧所有者继续修改。
     */
    public boolean fenceMatches(UUID tenantId, UUID runId, long fencingEpoch) {
        String expected = fencingEpoch + ":" + runId;
        String actual = redis.opsForValue().get(fenceKey(tenantId));
        return expected.equals(actual);
    }

    /**
     * 将候选写入临时重建索引，带所有者身份时遵循围栏约束。
     */
    public void upsertRebuild(UUID runId, UUID tenantId, UUID slaClockId,
                              String stage, Instant dueAt, Duration ttl) {
        if (dueAt == null) return;
        Long written = redis.execute(UPSERT_REBUILD, List.of(SlaDueIndexCodec.rebuildKey(runId, tenantId)),
                Long.toString(SlaDueIndexCodec.score(dueAt)),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage), Long.toString(ttlMillis(ttl)));
        if (!Long.valueOf(1L).equals(written)) {
            throw new IllegalStateException("SLA rebuild temporary key could not be renewed");
        }
    }

    /** 创建受租约约束的标记，使空集合的重建结果也可被验证。 */
    public boolean ensureRebuildKey(UUID runId, UUID tenantId, Duration ttl) {
        if (runId == null || tenantId == null) {
            throw new IllegalArgumentException("SLA rebuild key identity is invalid");
        }
        String key = SlaDueIndexCodec.rebuildKey(runId, tenantId);
        Boolean added = redis.opsForZSet().add(key, EMPTY_MARKER, 0D);
        Boolean expiry = redis.expire(key, ttl);
        return Boolean.TRUE.equals(expiry) && (Boolean.TRUE.equals(added)
                || Boolean.TRUE.equals(redis.hasKey(key)));
    }

    /**
     * 将候选写入临时重建索引，带所有者身份时遵循围栏约束。
     */
    public void upsertRebuild(UUID runId, UUID tenantId, UUID slaClockId,
                              String stage, Instant dueAt) {
        upsertRebuild(runId, tenantId, slaClockId, stage, dueAt, Duration.ofSeconds(30));
    }

    /**
     * 从临时索引移除失效候选，避免陈旧阶段进入正式索引。
     */
    public void removeRebuild(UUID runId, UUID tenantId, UUID slaClockId,
                              String stage, Duration ttl) {
        Long removed = redis.execute(REMOVE_REBUILD, List.of(SlaDueIndexCodec.rebuildKey(runId, tenantId)),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage), Long.toString(ttlMillis(ttl)));
        if (!Long.valueOf(1L).equals(removed)) {
            throw new IllegalStateException("SLA rebuild temporary key could not be renewed");
        }
    }

    /**
     * 从临时索引移除失效候选，避免陈旧阶段进入正式索引。
     */
    public void removeRebuild(UUID runId, UUID tenantId, UUID slaClockId, String stage) {
        removeRebuild(runId, tenantId, slaClockId, stage, Duration.ofSeconds(30));
    }

    /** 数据库与栅栏租约续期后，延长非空临时索引键的有效期。 */
    public boolean renewRebuildTtl(UUID runId, UUID tenantId, Duration ttl) {
        Long renewed = redis.execute(TOUCH_REBUILD, List.of(SlaDueIndexCodec.rebuildKey(runId, tenantId)),
                Long.toString(ttlMillis(ttl)));
        return Long.valueOf(1L).equals(renewed);
    }

    /** 只有 Redis 仍安装当前数据库栅栏时，才允许更新正式索引。 */
    public boolean upsertFenced(UUID tenantId, UUID runId, long fencingEpoch,
                                UUID slaClockId, String stage, Instant dueAt) {
        if (dueAt == null) return removeFenced(tenantId, runId, fencingEpoch, slaClockId, stage);
        Long written = redis.execute(FENCED_UPSERT,
                List.of(SlaDueIndexCodec.key(tenantId), fenceKey(tenantId)),
                fencingEpoch + ":" + runId, Long.toString(SlaDueIndexCodec.score(dueAt)),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage));
        return Long.valueOf(1L).equals(written);
    }

    /** 只有 Redis 仍安装当前数据库栅栏时，才允许删除正式索引候选。 */
    public boolean removeFenced(UUID tenantId, UUID runId, long fencingEpoch,
                                UUID slaClockId, String stage) {
        Long removed = redis.execute(FENCED_REMOVE,
                List.of(SlaDueIndexCodec.key(tenantId), fenceKey(tenantId)),
                fencingEpoch + ":" + runId, SlaDueIndexCodec.member(tenantId, slaClockId, stage));
        return Long.valueOf(1L).equals(removed);
    }

    /**
     * 读取当前临时索引数量，供结果完整性与统计核验。
     */
    public long rebuildSize(UUID runId, UUID tenantId) {
        Long size = redis.opsForZSet().zCard(SlaDueIndexCodec.rebuildKey(runId, tenantId));
        return size == null ? 0L : size;
    }

    /**
     * 核对围栏后切换重建索引，避免过期或不完整结果覆盖正式数据。
     */
    public void swapRebuild(UUID runId, UUID tenantId) {
        swapRebuild(runId, tenantId, null, -1L, null);
    }

    /**
     * 可选校验集合摘要后原子替换正式索引。
     * Lua 将删除、重命名及标记清理合为一次 Redis 操作，避免读者看见替换到一半的空标记。
     */
    public void swapRebuild(UUID runId, UUID tenantId, String expectedDigest,
                            long expectedCount) {
        swapRebuild(runId, tenantId, expectedDigest, expectedCount, null);
    }

    /**
     * 带栅栏的原子替换。
     * 传入 epoch 时，即使旧线程刚续过数据库租约，只要新线程已安装更新栅栏，Lua 也会拒绝旧线程。
     */
    public void swapRebuild(UUID runId, UUID tenantId, String expectedDigest,
                            long expectedCount, Long fencingEpoch) {
        String temporary = SlaDueIndexCodec.rebuildKey(runId, tenantId);
        String target = SlaDueIndexCodec.key(tenantId);
        if (expectedDigest != null) {
            RebuildSummary actual = rebuildSummary(runId, tenantId);
            if (actual.count() != expectedCount || !expectedDigest.equals(actual.digest())) {
                throw new IllegalStateException("SLA rebuild summary mismatch");
            }
        }
        String token = fencingEpoch == null ? "" : fencingEpoch + ":" + runId;
        Long swapped = redis.execute(ATOMIC_SWAP,
                List.of(temporary, target, fenceKey(tenantId)), EMPTY_MARKER, token);
        if (!Long.valueOf(1L).equals(swapped)) {
            throw new IllegalStateException("SLA rebuild atomic swap failed");
        }
    }

    /** 计算确定性的成员及数量摘要，用于拒绝过期的重建结果。 */
    public RebuildSummary rebuildSummary(UUID runId, UUID tenantId) {
        return summary(SlaDueIndexCodec.rebuildKey(runId, tenantId));
    }

    /** 替换后追平增量变更时，计算正式索引的摘要。 */
    public RebuildSummary formalSummary(UUID tenantId) {
        return summary(SlaDueIndexCodec.key(tenantId));
    }

    /**
     * 统计当前权限范围内的待处理工单数量，供工作台展示。
     */
    private RebuildSummary summary(String key) {
        Set<ZSetOperations.TypedTuple<String>> values = redis.opsForZSet().rangeWithScores(key, 0, -1);
        List<ZSetOperations.TypedTuple<String>> ordered = values == null
                ? new ArrayList<>() : new ArrayList<>(values);
        ordered.removeIf(tuple -> tuple == null || tuple.getValue() == null
                || EMPTY_MARKER.equals(tuple.getValue()));
        ordered.sort(Comparator.comparing(ZSetOperations.TypedTuple<String>::getValue)
                .thenComparing(tuple -> tuple.getScore() == null ? 0D : tuple.getScore()));
        TreeMap<String, Long> members = new TreeMap<>();
        for (ZSetOperations.TypedTuple<String> tuple : ordered) {
            members.put(tuple.getValue(), Math.round(tuple.getScore() == null ? 0D : tuple.getScore()));
        }
        return new RebuildSummary(members.size(), digest(members));
    }

    /** 对 MySQL 派生的预期集合使用相同的规范摘要算法。 */
    public static String digest(Map<String, Long> members) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            members.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String line = entry.getKey() + "#" + Long.toString(entry.getValue()) + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            });
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * 清理本次失败重建的临时内容，避免遗留无效索引。
     */
    public void discardRebuild(UUID runId, UUID tenantId) {
        redis.delete(SlaDueIndexCodec.rebuildKey(runId, tenantId));
    }

    /**
     * 将租约时长转为合法毫秒 TTL，供 Redis 设置存活时间。
     */
    private static long ttlMillis(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("SLA rebuild TTL must be positive");
        }
        long millis = ttl.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException("SLA rebuild TTL must be at least one millisecond");
        }
        return millis;
    }

    /**
     * Redis 临时重建索引摘要，供切换前核对结果规模。
     */
    public record RebuildSummary(long count, String digest) { }
}
