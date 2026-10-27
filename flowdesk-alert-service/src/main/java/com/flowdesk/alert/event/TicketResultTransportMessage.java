package com.flowdesk.alert.event;

import java.util.Map;

/**
 * RocketMQ 适配器交给契约校验器的传输快照，包含路由、消息键、原始正文和用户属性。
 *
 * <p>这里的数据尚未可信，不能据 tenant 属性直接访问业务库。构造时复制正文和属性，读取正文时再次复制，
 * 避免 SDK 或调用方修改原消息后，校验看到的内容与后续处理的内容不一致。
 */
public record TicketResultTransportMessage(
        String topic,
        String consumerGroup,
        String keys,
        String tags,
        byte[] body,
        Map<String, String> properties) {
    /**
     * 构造不可变快照并复制可变字节数组，防止调用方在校验完成后修改原文、摘要或认证事实。
     */
    public TicketResultTransportMessage {
        body = body == null ? null : body.clone();
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /**
     * 返回原始正文的防御性副本，防止外部修改污染已验证或待恢复的消息快照。
     */
    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }
}
