package com.flowdesk.notification.event;

import java.util.Optional;

/** 携带信任边界的确定性消息错误，区分不可归属的报文与可记录租户拒绝事实的报文。 */
public class NotificationContractViolation extends RuntimeException {
    private final String code;
    private final TrustBoundary boundary;
    private final NotificationEnvelope envelope;

    /**
     * 契约校验中的租户信任边界，决定非法消息是否允许写入租户内拒绝事实。
     */
    public enum TrustBoundary {
        PRE_TRUST,
        TRUSTED_TENANT
    }

    /**
     * 以租户尚未可信的边界构造契约异常，保留错误码和可选原因供入口直接丢弃非法传输。
     */
    public NotificationContractViolation(String code) {
        this(code, null, TrustBoundary.PRE_TRUST, null);
    }

    /**
     * 以租户尚未可信的边界构造契约异常，保留错误码和可选原因供入口直接丢弃非法传输。
     */
    public NotificationContractViolation(String code, Throwable cause) {
        this(code, cause, TrustBoundary.PRE_TRUST, null);
    }

    /**
     * 保存指定错误码、原因和信任边界；可信异常保留原信封，供入口记录对应租户的拒绝事实。
     */
    private NotificationContractViolation(String code, Throwable cause,
                                          TrustBoundary boundary,
                                          NotificationEnvelope envelope) {
        super(code, cause);
        this.code = code;
        this.boundary = boundary;
        this.envelope = envelope;
    }

    /**
     * 构造携带可信信封的异常，供入口在相应租户中记录拒绝事实。
     */
    public static NotificationContractViolation trusted(String code,
                                                        NotificationEnvelope envelope) {
        return new NotificationContractViolation(code, null,
                TrustBoundary.TRUSTED_TENANT, envelope);
    }

    /**
     * 构造携带可信信封的异常，供入口在相应租户中记录拒绝事实。
     */
    public static NotificationContractViolation trusted(String code,
                                                        Throwable cause,
                                                        NotificationEnvelope envelope) {
        return new NotificationContractViolation(code, cause,
                TrustBoundary.TRUSTED_TENANT, envelope);
    }

    /**
     * 取得稳定错误码，供调用方分类处理并保存有限长度错误信息。
     */
    public String code() {
        return code;
    }

    /**
     * 返回校验失败时的信任边界，供入口决定是否可以记录租户事实。
     */
    public TrustBoundary boundary() {
        return boundary;
    }

    /**
     * 判断异常是否保留可信信封，决定能否写入租户内业务事实。
     */
    public boolean hasTrustedEnvelope() {
        return boundary == TrustBoundary.TRUSTED_TENANT && envelope != null;
    }

    /**
     * 取得保留原消息身份与内容的信封，供拒绝记录和恢复路径复用。
     */
    public Optional<NotificationEnvelope> envelope() {
        return Optional.ofNullable(envelope);
    }
}
