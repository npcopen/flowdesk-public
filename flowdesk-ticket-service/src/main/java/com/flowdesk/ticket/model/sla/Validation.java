package com.flowdesk.ticket.model.sla;

import java.util.UUID;

/**
 * 资格校验结果，包含是否允许继续及不满足条件的原因。
 */
public record Validation(boolean eligible, UUID recipientUserId,
                         Long qualificationVersion, String reasonCode) {
    /**
     * 创建或检查有效校验结果，供后续成功分支使用。
     */
    public static Validation valid(UUID recipient, long version) {
        return new Validation(true, recipient, version, "ELIGIBLE");
    }
    /**
     * 构造输入不合法的结果，供上层返回约定业务或契约错误。
     */
    public static Validation invalid(String reason) {
        return new Validation(false, null, null, reason);
    }
}
