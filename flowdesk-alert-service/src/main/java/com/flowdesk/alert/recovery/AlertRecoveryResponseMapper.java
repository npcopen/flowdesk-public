package com.flowdesk.alert.recovery;
import com.flowdesk.alert.model.*;
import java.util.HexFormat;
/**
 * 将告警恢复数据库投影转换为对外视图，集中处理摘要编码、操作人信息和状态字段映射。
 */
public final class AlertRecoveryResponseMapper {
    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertRecoveryResponseMapper() {}
    /**
     * 把恢复记录投影转换为 API 视图，将业务意图编码为十六进制并按需组合人工操作人信息。
     */
    public static AlertRecoveryView view(AlertRecoveryRow f) {
        return new AlertRecoveryView(
                f.id(),
                "ALERT",
                f.direction(),
                f.source(),
                HexFormat.of().formatHex(f.intent()),
                f.state(),
                f.attempts(),
                f.next(),
                f.error(),
                f.summary(),
                f.dead(),
                f.required(),
                f.replays(),
                f.replayedAt(),
                f.replayedBy() == null
                        ? null
                        : new AlertRecoveryView.ReplayedBy(f.replayedType(), f.replayedBy()),
                f.reason(),
                f.resolved(),
                f.version(),
                f.created(),
                f.updated());
    }
}
