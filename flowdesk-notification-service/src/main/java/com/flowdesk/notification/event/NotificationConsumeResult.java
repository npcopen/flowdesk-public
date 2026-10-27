package com.flowdesk.notification.event;

/** 通知消费结果，区分可确认的确定性处理与需要 Broker 重试的暂时失败。 */
public record NotificationConsumeResult(Disposition disposition, String code) {
    /**
     * 消息处置分类，供外层监听器直接映射为确认或重试。
     */
    public enum Disposition { ACK, RETRY }

    /**
     * 构造可向 Broker 确认的结果，错误码说明接纳或拒绝的业务原因。
     */
    public static NotificationConsumeResult ack(String code) {
        return new NotificationConsumeResult(Disposition.ACK, code);
    }

    /**
     * 构造需要代理重投的结果，将暂时性故障交由外层有界重试处理。
     */
    public static NotificationConsumeResult retry(String code) {
        return new NotificationConsumeResult(Disposition.RETRY, code);
    }
}
