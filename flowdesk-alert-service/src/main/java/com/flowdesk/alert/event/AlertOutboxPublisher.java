package com.flowdesk.alert.event;
import com.flowdesk.alert.service.AlertOutboxDeliveryService;
import com.flowdesk.alert.model.*;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 Alert 已持久化的“请求建单”和“故障恢复”事件发送给 Ticket 服务。
 *
 * <p>每条记录先在短事务中领取，再在事务外调用 Broker，最后用另一短事务结算结果。
 * 网络发送不占用数据库锁；工作线程中断后，其他实例可以接管过期租约。Broker 已收取但确认丢失时仍可能重发，
 * 因此始终复用原消息身份与报文字节，由 Ticket 的 Inbox 负责消除重复副作用。
 */
final class AlertOutboxPublisher implements AutoCloseable {
    private static final DateTimeFormatter MICROS =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    private final AlertOutboxDeliveryService repository;
    private final Clock clock;
    private final DefaultMQProducer createProducer, recoveryProducer;
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    AlertOutboxPublisher(
            AlertOutboxDeliveryService repository,
            AlertOutboxProperties p) {
        this(repository, p, Clock.systemUTC());
    }

    /**
     * 绑定发件箱领取能力、RocketMQ 生产者和时钟；发送与数据库结算分别执行，以 token、版本和租约保护发送轮次。
     */
    AlertOutboxPublisher(
            AlertOutboxDeliveryService r,
            AlertOutboxProperties p,
            Clock c) {
        repository = r;
        clock = c;
        p.validate();
        var hook = new AclClientRPCHook(new SessionCredentials(p.getAccessKey(), p.getSecretKey()));
        createProducer = producer(AlertEventContract.TICKET_CREATE_REQUESTED, p, hook, "create");
        recoveryProducer = producer(AlertEventContract.INCIDENT_RECOVERED, p, hook, "recovery");
    }

    /** SDK 不额外重试；每次投递机会都由数据库 attempt_count 计数，避免两层重试突破持久化上限。 */
    private static DefaultMQProducer producer(
            AlertEventContract.Binding b,
            AlertOutboxProperties p,
            AclClientRPCHook hook,
            String suffix) {
        var producer = new DefaultMQProducer(b.producerGroup(), hook);
        producer.setNamesrvAddr(p.getNameServer().trim());
        producer.setInstanceName("flowdesk-alert-" + suffix + '-' + UUID.randomUUID());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(Math.toIntExact(AlertEventContract.SEND_TIMEOUT.toMillis()));
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return producer;
    }

    /** 两个生产者作为一组启动；任一初始化失败就关闭全部，避免仅部分事件方向可用。 */
    public synchronized void start() throws Exception {
        if (started.get()) return;
        try {
            createProducer.start();
            recoveryProducer.start();
            started.set(true);
        } catch (Exception e) {
            createProducer.shutdown();
            recoveryProducer.shutdown();
            throw e;
        }
    }

    /**
     * 扫描跨租户候选时要求线程没有遗留事务或租户上下文，再按持久化记录逐条打开租户作用域。
     * 候选只是扫描快照，只有成功领取的线程才可发送；结算仍需检查 token、版本和租约时间，
     * 防止发送阻塞过久的旧线程覆盖接管者。已耗尽八次机会的候选只领取恢复结算权，不再发送。
     */
    Run publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TenantContextHolder.currentOrNull() != null)
            throw new IllegalStateException("Alert publisher requires clean worker context");
        Instant scan = clock.instant();
        int scanned = 0, published = 0, recovered = 0;
        for (var c : repository.candidates(scan, scan.minus(AlertEventContract.CLAIM_LEASE))) {
            scanned++;
            TenantContext context =
                    TenantContext.ofPersistedTask(
                            c.tenantId(),
                            ActorRef.service("flowdesk-alert-service"),
                            c.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                String token = UUID.randomUUID().toString();
                Instant claimedAt = clock.instant();
                var claimed =
                        c.attemptCount() == 8
                                                ? repository.claimRecovery(
                                                        c,
                                                        token,
                                                        claimedAt,
                                                        claimedAt.minus(
                                                                AlertEventContract.CLAIM_LEASE))
                                                : repository.claim(
                                                        c,
                                                        token,
                                                        claimedAt,
                                                        claimedAt.minus(
                                                                AlertEventContract.CLAIM_LEASE));
                if (claimed == null || claimed.isEmpty()) continue;
                var row = claimed.get();
                if (c.attemptCount() == 8) {
                    // 上次可能在第八次发送后崩溃；保留未知结果进入人工恢复，不能再补发第九次。
                    if (Boolean.TRUE.equals(
                            repository.recover(
                                                    row,
                                                    clock.instant(),
                                                    clock.instant()
                                                            .minus(AlertEventContract.CLAIM_LEASE),
                                                    "BROKER_RESULT_UNKNOWN"))) recovered++;
                    continue;
                }
                boolean ok = false;
                String code = "BROKER_RESULT_UNKNOWN";
                try {
                    ok = send(row);
                    code = ok ? null : "BROKER_SEND_NOT_CONFIRMED";
                } catch (IllegalArgumentException invalid) {
                    code = "OUTBOX_CONTRACT_INVALID";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignoredFailure) {
                    // 网络异常无法证明 Broker 未收取；保留“结果未知”，后续仍以同一身份重试。
                }
                Instant done = clock.instant(), cutoff = done.minus(AlertEventContract.CLAIM_LEASE);
                if (ok) {
                    if (Boolean.TRUE.equals(
                            repository.published(row, done, cutoff)))
                        published++;
                } else if (row.attemptCount() < 8) {
                    String safe = code;
                    var delay = AlertEventContract.FAILURE_BACKOFFS.get(row.attemptCount() - 1);
                    repository.pending(row, done, cutoff, done.plus(delay), safe);
                } else {
                    String safe = code;
                    if (Boolean.TRUE.equals(
                            repository.recover(row, done, cutoff, safe)))
                        recovered++;
                }
            }
        }
        return new Run(scanned, published, recovered);
    }

    /**
     * 直接发送持久化正文，发送前核对大小、键长度、UTF-8 和正文摘要，再重建契约信封。
     * 只有 SEND_OK 才能申请标记为 PUBLISHED；它表示 Broker 确认收取，不代表 Ticket 已完成业务消费。
     */
    private boolean send(AlertOutboxClaim r) throws Exception {
        AlertEventContract.Binding b = binding(r.contractRef());
        byte[] body = r.body();
        if (body.length == 0
                || body.length > AlertEventContract.MAX_BODY_BYTES
                || r.intent().length != 32
                || r.publicationKey().length != 32)
            throw new IllegalArgumentException("Invalid outbox keys/body");
        StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
        if (!MessageDigest.isEqual(hash, r.hash()))
            throw new IllegalArgumentException("Invalid body hash");
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("fd_envelope_version", "1");
        p.put("fd_event_type", b.eventType());
        p.put("fd_contract_ref", b.contractRef());
        p.put("fd_contract_version", "1");
        p.put("fd_producer", b.producer());
        p.put("fd_tenant_id", r.tenantId().toString());
        p.put("fd_message_id", r.messageId().toString());
        p.put("fd_business_intent_key", HexFormat.of().formatHex(r.intent()));
        p.put("fd_aggregate_type", "INCIDENT");
        p.put("fd_aggregate_id", r.incidentId().toString());
        p.put("fd_correlation_id", r.correlationId().toString());
        if (r.causationId() != null) p.put("fd_causation_id", r.causationId().toString());
        p.put("fd_occurred_at", MICROS.format(r.createdAt()));
        p.put("fd_body_sha256", HexFormat.of().formatHex(hash));
        Message m = new Message(b.topic(), body);
        m.setKeys(r.messageId().toString());
        m.setWaitStoreMsgOK(true);
        p.forEach(m::putUserProperty);
        DefaultMQProducer producer =
                b == AlertEventContract.TICKET_CREATE_REQUESTED ? createProducer : recoveryProducer;
        SendResult result = producer.send(m, AlertEventContract.SEND_TIMEOUT.toMillis());
        return result != null && result.getSendStatus() == SendStatus.SEND_OK;
    }

    /**
     * 根据发件箱契约选择冻结的 Topic、Tag 与生产者绑定，未知契约直接拒绝发送。
     */
    private static AlertEventContract.Binding binding(String ref) {
        if (AlertEventContract.TICKET_CREATE_REQUESTED.contractRef().equals(ref))
            return AlertEventContract.TICKET_CREATE_REQUESTED;
        if (AlertEventContract.INCIDENT_RECOVERED.contractRef().equals(ref))
            return AlertEventContract.INCIDENT_RECOVERED;
        throw new IllegalArgumentException("Unexpected contract");
    }

    /**
     * 关闭 RocketMQ 生产者并释放底层网络资源，供服务关闭及测试清理调用。
     */
    @Override
    public synchronized void close() {
        if (started.compareAndSet(true, false)) {
            createProducer.shutdown();
            recoveryProducer.shutdown();
        }
    }

    /** 本轮扫描及成功结算数量；scanned 包含被其他工作线程抢先领取的候选。 */
    record Run(int scanned, int published, int recovered) {}
}
