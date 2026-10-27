package com.flowdesk.workflow.outbox;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流反向事件的传输模型，保留 Topic、消息键、信封属性及原始消息体。
 */
public record WorkflowOutboxMessage(
        String topic, String keys, byte[] body, Map<String, String> userProperties) {

    /**
     * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
     */
    public WorkflowOutboxMessage {
        body = body.clone();
        userProperties = Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
    }

    /**
     * 返回消息体字节的防御性副本，避免调用方通过数组修改已保存事实。
     */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
