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

/** Redis ZSET adapter for candidate ordering only. */
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

    public RedisSlaDueIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

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

    @Override
    public void remove(UUID tenantId, UUID slaClockId, String stage) {
        redis.opsForZSet().remove(SlaDueIndexCodec.key(tenantId),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage));
    }

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
                // A malformed technical member is ignored by the candidate reader;
                // the bounded MySQL safety sweep remains authoritative.
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void clear(UUID tenantId) {
        redis.delete(SlaDueIndexCodec.key(tenantId));
    }

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

    public String key(UUID tenantId) {
        return SlaDueIndexCodec.key(tenantId);
    }

    public String rebuildKey(UUID runId, UUID tenantId) {
        return SlaDueIndexCodec.rebuildKey(runId, tenantId);
    }

    /** Redis-side fence key used by the final atomic swap. */
    public String fenceKey(UUID tenantId) {
        return "flowdesk:ticket:sla:fence:{" + SlaDueIndexCodec.tenantHex(tenantId) + "}";
    }

    /** Installs a fence only when its epoch is newer than the stored epoch. */
    public boolean installFence(UUID tenantId, UUID runId, long fencingEpoch, Duration ttl) {
        if (tenantId == null || runId == null || fencingEpoch < 1) {
            throw new IllegalArgumentException("Invalid SLA rebuild fence");
        }
        String token = fencingEpoch + ":" + runId;
        Long installed = redis.execute(INSTALL_FENCE, List.of(fenceKey(tenantId)),
                Long.toString(fencingEpoch), token, Long.toString(ttlMillis(ttl)));
        return Long.valueOf(1L).equals(installed);
    }

    /** Compatibility overload for narrow adapters that have no configured lease. */
    public boolean installFence(UUID tenantId, UUID runId, long fencingEpoch) {
        return installFence(tenantId, runId, fencingEpoch, Duration.ofSeconds(30));
    }

    /** Renews only the exact fence owner.  A missing or newer fence is lease loss. */
    public boolean renewFence(UUID tenantId, UUID runId, long fencingEpoch, Duration ttl) {
        if (tenantId == null || runId == null || fencingEpoch < 1) {
            throw new IllegalArgumentException("Invalid SLA rebuild fence");
        }
        Long renewed = redis.execute(RENEW_FENCE, List.of(fenceKey(tenantId)),
                fencingEpoch + ":" + runId, Long.toString(ttlMillis(ttl)));
        return Long.valueOf(1L).equals(renewed);
    }

    public boolean fenceMatches(UUID tenantId, UUID runId, long fencingEpoch) {
        String expected = fencingEpoch + ":" + runId;
        String actual = redis.opsForValue().get(fenceKey(tenantId));
        return expected.equals(actual);
    }

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

    /** Creates a lease-bound marker so an empty rebuild is still observable. */
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

    public void upsertRebuild(UUID runId, UUID tenantId, UUID slaClockId,
                              String stage, Instant dueAt) {
        upsertRebuild(runId, tenantId, slaClockId, stage, dueAt, Duration.ofSeconds(30));
    }

    public void removeRebuild(UUID runId, UUID tenantId, UUID slaClockId,
                              String stage, Duration ttl) {
        Long removed = redis.execute(REMOVE_REBUILD, List.of(SlaDueIndexCodec.rebuildKey(runId, tenantId)),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage), Long.toString(ttlMillis(ttl)));
        if (!Long.valueOf(1L).equals(removed)) {
            throw new IllegalStateException("SLA rebuild temporary key could not be renewed");
        }
    }

    public void removeRebuild(UUID runId, UUID tenantId, UUID slaClockId, String stage) {
        removeRebuild(runId, tenantId, slaClockId, stage, Duration.ofSeconds(30));
    }

    /** Extends a nonempty temporary key after a database/fence lease renewal. */
    public boolean renewRebuildTtl(UUID runId, UUID tenantId, Duration ttl) {
        Long renewed = redis.execute(TOUCH_REBUILD, List.of(SlaDueIndexCodec.rebuildKey(runId, tenantId)),
                Long.toString(ttlMillis(ttl)));
        return Long.valueOf(1L).equals(renewed);
    }

    /** Applies a formal-index update only while the exact database fence remains installed. */
    public boolean upsertFenced(UUID tenantId, UUID runId, long fencingEpoch,
                                UUID slaClockId, String stage, Instant dueAt) {
        if (dueAt == null) return removeFenced(tenantId, runId, fencingEpoch, slaClockId, stage);
        Long written = redis.execute(FENCED_UPSERT,
                List.of(SlaDueIndexCodec.key(tenantId), fenceKey(tenantId)),
                fencingEpoch + ":" + runId, Long.toString(SlaDueIndexCodec.score(dueAt)),
                SlaDueIndexCodec.member(tenantId, slaClockId, stage));
        return Long.valueOf(1L).equals(written);
    }

    /** Removes a formal candidate only while the exact database fence remains installed. */
    public boolean removeFenced(UUID tenantId, UUID runId, long fencingEpoch,
                                UUID slaClockId, String stage) {
        Long removed = redis.execute(FENCED_REMOVE,
                List.of(SlaDueIndexCodec.key(tenantId), fenceKey(tenantId)),
                fencingEpoch + ":" + runId, SlaDueIndexCodec.member(tenantId, slaClockId, stage));
        return Long.valueOf(1L).equals(removed);
    }

    public long rebuildSize(UUID runId, UUID tenantId) {
        Long size = redis.opsForZSet().zCard(SlaDueIndexCodec.rebuildKey(runId, tenantId));
        return size == null ? 0L : size;
    }

    public void swapRebuild(UUID runId, UUID tenantId) {
        swapRebuild(runId, tenantId, null, -1L, null);
    }

    /**
     * Atomically replaces the formal key after an optional summary check.  The
     * Lua operation keeps delete/rename/marker cleanup in one Redis command so
     * readers can never observe a half-swapped empty marker.
     */
    public void swapRebuild(UUID runId, UUID tenantId, String expectedDigest,
                            long expectedCount) {
        swapRebuild(runId, tenantId, expectedDigest, expectedCount, null);
    }

    /**
     * Fenced atomic replacement. When an epoch is supplied the Redis Lua
     * operation rejects a stale worker even if it renewed its database lease
     * immediately before another worker installed a newer fence.
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

    /** Deterministic member/count digest used to fence a stale rebuild. */
    public RebuildSummary rebuildSummary(UUID runId, UUID tenantId) {
        return summary(SlaDueIndexCodec.rebuildKey(runId, tenantId));
    }

    /** Digest of the formal key after post-swap catch-up. */
    public RebuildSummary formalSummary(UUID tenantId) {
        return summary(SlaDueIndexCodec.key(tenantId));
    }

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

    /** Same canonical digest algorithm for the MySQL-derived expected set. */
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

    public void discardRebuild(UUID runId, UUID tenantId) {
        redis.delete(SlaDueIndexCodec.rebuildKey(runId, tenantId));
    }

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

    public record RebuildSummary(long count, String digest) { }
}
