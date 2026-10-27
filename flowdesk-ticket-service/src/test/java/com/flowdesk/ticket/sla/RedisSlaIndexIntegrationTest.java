package com.flowdesk.ticket.sla;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis SLA 索引适配器的边界契约测试，通过替身核验租户键、候选编码和异常成员处理。
 * 真实 Redis 上的围栏与索引切换行为由 M8 端到端测试覆盖。
 */
class RedisSlaIndexIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CLOCK_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CLOCK_B = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /**
     * 验证租户键隔离、同毫秒候选和非法成员处理。
     */
    @Test
    void tenantKeysSameMillisecondCandidatesAndMalformedMembersAreHandledSafely() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        RedisSlaDueIndex index = new RedisSlaDueIndex(redis);
        Instant dueAt = Instant.parse("2026-08-30T01:02:03.123999Z");
        long score = SlaDueIndexCodec.score(dueAt);
        String keyA = SlaDueIndexCodec.key(TENANT_A);
        String keyB = SlaDueIndexCodec.key(TENANT_B);
        String memberA = SlaDueIndexCodec.member(TENANT_A, CLOCK_A, "BREACH");
        String memberB = SlaDueIndexCodec.member(TENANT_B, CLOCK_B, "BREACH");

        index.upsert(TENANT_A, CLOCK_A, "BREACH", dueAt);
        index.upsert(TENANT_B, CLOCK_B, "BREACH", dueAt);
        verify(zset).add(keyA, memberA, (double) score);
        verify(zset).add(keyB, memberB, (double) score);
        assertThat(keyA).isNotEqualTo(keyB);
        assertThat(SlaDueIndexCodec.score(Instant.parse("2026-08-30T01:02:03.123001Z")))
                .isEqualTo(score);

        Set<ZSetOperations.TypedTuple<String>> raw = new LinkedHashSet<>();
        raw.add(new DefaultTypedTuple<>(memberA, (double) score));
        raw.add(new DefaultTypedTuple<>(memberB, (double) score));
        raw.add(new DefaultTypedTuple<>("not-a-candidate", (double) score));
        when(zset.rangeByScoreWithScores(keyA, Double.NEGATIVE_INFINITY, (double) score, 0, 10))
                .thenReturn(raw);

        assertThat(index.due(TENANT_A, dueAt, 10)).containsExactly(
                new SlaDueIndex.Candidate(TENANT_A, CLOCK_A, "BREACH", score));

        index.remove(TENANT_A, CLOCK_A, "BREACH");
        index.clear(TENANT_A);
        verify(zset).remove(keyA, memberA);
        verify(redis).delete(keyA);
    }

    /**
     * 验证临时键与正式键仅共享各自租户的 Redis 哈希槽。
     */
    @Test
    void temporaryAndFormalKeysShareOnlyTheirTenantHashSlot() {
        UUID run = UUID.fromString("55555555-5555-5555-5555-555555555555");
        String formalA = SlaDueIndexCodec.key(TENANT_A);
        String temporaryA = SlaDueIndexCodec.rebuildKey(run, TENANT_A);
        String temporaryB = SlaDueIndexCodec.rebuildKey(run, TENANT_B);

        assertThat(temporaryA).contains("{" + SlaDueIndexCodec.tenantHex(TENANT_A) + "}");
        assertThat(formalA).contains("{" + SlaDueIndexCodec.tenantHex(TENANT_A) + "}");
        assertThat(temporaryB).doesNotContain("{" + SlaDueIndexCodec.tenantHex(TENANT_A) + "}");
    }
}
