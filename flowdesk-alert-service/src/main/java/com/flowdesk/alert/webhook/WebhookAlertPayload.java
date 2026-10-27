package com.flowdesk.alert.webhook;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示 SCHEMA-041 的告警事件字段，由控制器在原始报文验签通过后解析。
 *
 * <p>parse 接收认证快照中的同一份原始字节，严格检查 UTF-8、JSON 结构、字段集合和字段取值。
 * eventType 只允许 FIRING 或 RESOLVED，occurredAt 使用带六位小数的 UTC 时间；summary 字段必须存在，
 * 但可以为 null。解析只确定报文符合契约，事件去重、nonce 防重放和故障状态变化由后续接纳事务处理。
 */
public record WebhookAlertPayload(
        String eventId,
        String eventType,
        String fingerprint,
        String cycleRef,
        Instant occurredAt,
        String summary) {
    private static final ObjectMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> FIELDS =
            Set.of("eventId", "eventType", "fingerprint", "cycleRef", "occurredAt", "summary");
    private static final Pattern UTC_MICROS =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");

    /** 严格解析完整报文；任何格式问题统一抛出 InvalidPayload，由控制器映射为 422 业务报文错误。 */
    public static WebhookAlertPayload parse(byte[] body) {
        try {
            // 非法 UTF-8 必须报错，不能替换成占位字符后继续解释已签名的数据。
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(body))
                            .toString();
            JsonNode root;
            try (var parser = JSON.createParser(text)) {
                root = JSON.readTree(parser);
                // 一个 JSON 对象后不能再拼接其他值；重复字段另由解析器的严格重复检测拒绝。
                require(parser.nextToken() == null);
            }
            require(root != null && root.isObject());
            Set<String> actual = new java.util.HashSet<>();
            root.properties().forEach(e -> actual.add(e.getKey()));
            // 字段集合必须完全匹配契约；重复字段由解析器拒绝，不容许客户端隐式扩展请求语义。
            require(actual.equals(FIELDS));
            String eventId = requiredText(root, "eventId", 1, 128);
            String eventType = requiredText(root, "eventType", 1, 16);
            require("FIRING".equals(eventType) || "RESOLVED".equals(eventType));
            String fingerprint = requiredText(root, "fingerprint", 1, 255);
            String cycleRef = requiredText(root, "cycleRef", 1, 128);
            String occurredText = requiredText(root, "occurredAt", 1, 64);
            require(UTC_MICROS.matcher(occurredText).matches());
            Instant occurredAt = Instant.parse(occurredText);
            JsonNode summaryNode = root.get("summary");
            String summary = null;
            if (!summaryNode.isNull()) {
                require(summaryNode.isString());
                summary = summaryNode.stringValue();
                require(validScalarText(summary, 0, 1000));
            }
            return new WebhookAlertPayload(
                    eventId, eventType, fingerprint, cycleRef, occurredAt, summary);
        } catch (InvalidPayload invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new InvalidPayload();
        }
    }

    /**
     * 从 JSON 对象读取必填文本并校验长度及字符内容，字段缺失或类型错误都按载荷契约拒绝。
     */
    private static String requiredText(JsonNode root, String field, int min, int max) {
        JsonNode value = root.get(field);
        require(value != null && value.isString());
        String text = value.stringValue();
        require(validScalarText(text, min, max));
        return text;
    }

    /**
     * 按 Unicode 码点计算字段长度，避免把一个补充字符误算成两个。
     * 同时拒绝孤立代理项和控制字符，但保留契约允许的制表符、换行及回车。
     */
    private static boolean validScalarText(String value, int min, int max) {
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) return false;
        for (int i = 0; i < value.length(); ) {
            char c = value.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c)
                        || i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i += 2;
                continue;
            }
            if (c != '\t' && c != '\n' && c != '\r' && Character.isISOControl(c)) return false;
            i++;
        }
        return true;
    }

    /**
     * 校验载荷字段必须满足的模式条件；任一条件不满足即抛出统一的 InvalidPayload，终止业务解析。
     */
    private static void require(boolean condition) {
        if (!condition) throw new InvalidPayload();
    }

    /** 仅标记报文不符合契约，不携带原始正文或底层解析器细节，供控制器统一转换响应。 */
    public static final class InvalidPayload extends RuntimeException {}
}
