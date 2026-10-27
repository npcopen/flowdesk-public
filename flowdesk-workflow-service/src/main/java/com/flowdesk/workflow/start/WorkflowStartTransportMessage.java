package com.flowdesk.workflow.start;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提交给严格启动入口的 Broker 原始投递事实；consumerGroup 由消息适配器按自身配置填入。
 */
public record WorkflowStartTransportMessage(
        String topic,
        String consumerGroup,
        String keys,
        String tags,
        byte[] body,
        Map<String, String> userProperties) {

    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public WorkflowStartTransportMessage {
        body = body == null ? null : body.clone();
        userProperties = userProperties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }

    /**
     * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }
}
