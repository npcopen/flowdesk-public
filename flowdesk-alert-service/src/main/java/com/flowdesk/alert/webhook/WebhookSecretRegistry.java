package com.flowdesk.alert.webhook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 启动时从运行时 JSON 文件加载集成 HMAC 密钥，供 Webhook 认证过滤器按 integrationId 查找。
 *
 * <p>文件只能包含 dummySecret 和 integrations：前者是认证失败路径仍需执行 HMAC 时使用的虚拟密钥，
 * 后者把规范格式的集成 UUID 映射到真实密钥。所有值使用标准 Base64 编码，解码后至少为 32 字节。
 * 空 integrations 可启动，但无法认证真实集成；虚拟密钥始终不能替代缺失的真实密钥。
 *
 * <p>配置或文件无效会阻止 Bean 创建，避免静默降级为无验签入口。密钥保存在内存中，返回时复制字节数组，
 * 防止调用方修改后续请求使用的密钥；本类不负责运行期间重新加载文件。
 */
public final class WebhookSecretRegistry {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final byte[] dummySecret;
    private final Map<UUID, byte[]> integrationSecrets;

    /**
     * 从配置的密钥文件载入集成签名秘密并检查格式；保留虚拟秘密以覆盖缺少真实候选时的 HMAC 计算。
     */
    public WebhookSecretRegistry(WebhookProperties properties) {
        properties.validateEnabled();
        try {
            // 先校验顶层结构和字段类型，拒绝多余字段，避免配置拼写错误被忽略。
            JsonNode root = JSON.readTree(Files.readAllBytes(Path.of(properties.getSecretsFile())));
            if (root == null
                    || !root.isObject()
                    || root.size() != 2
                    || root.get("dummySecret") == null
                    || !root.get("dummySecret").isString()
                    || root.get("integrations") == null
                    || !root.get("integrations").isObject()) {
                throw invalid();
            }
            dummySecret = decode(root.get("dummySecret").stringValue());
            Map<UUID, byte[]> values = new HashMap<>();
            var fields = root.get("integrations").properties();
            for (var entry : fields) {
                UUID id = UUID.fromString(entry.getKey());
                // 要求 UUID 文本与标准输出一致；不同文本形式不能被当成多个密钥配置项。
                if (!id.toString().equals(entry.getKey())
                        || !entry.getValue().isString()
                        || values.put(id, decode(entry.getValue().stringValue())) != null) {
                    throw invalid();
                }
            }
            integrationSecrets = Map.copyOf(values);
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw invalid();
        }
    }

    /** 返回虚拟密钥的副本，仅用于补齐认证失败路径中的计算，不代表找到了集成凭据。 */
    public byte[] dummySecret() {
        return dummySecret.clone();
    }

    /** 查找真实集成密钥并返回副本；缺失必须由过滤器判为认证失败。 */
    public Optional<byte[]> secret(UUID integrationId) {
        byte[] value = integrationSecrets.get(integrationId);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    /**
     * 将配置中的签名密钥从 Base64 解码并校验长度，非法密钥阻止认证组件启动。
     */
    private static byte[] decode(String encoded) {
        byte[] value = Base64.getDecoder().decode(encoded);
        if (value.length < 32) throw invalid();
        return value;
    }

    /** 配置错误只携带固定说明，不把读取失败的文件内容或密钥附在异常消息中。 */
    private static IllegalStateException invalid() {
        return new IllegalStateException("Alert Webhook secret registry is invalid");
    }
}
