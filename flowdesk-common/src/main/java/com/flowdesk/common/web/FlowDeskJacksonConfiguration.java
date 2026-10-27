package com.flowdesk.common.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * MVC 服务共用的 Jackson 3 序列化策略。
 * 拒绝重复的 JSON 键，并将响应中的 UTC 时间统一为六位小数加 {@code Z}，确保 API 时间格式稳定。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(JsonMapper.class)
public class FlowDeskJacksonConfiguration {

    private static final DateTimeFormatter FLOWDESK_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    /**
     * 定制共享 JSON 映射器的时间和字段策略，使服务接口遵循统一序列化契约。
     */
    @Bean
    public JsonMapperBuilderCustomizer flowDeskJsonMapperBuilderCustomizer() {
        return builder -> {
            builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
            builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            SimpleModule module = new SimpleModule("flowdesk-instant-format");
            module.addSerializer(Instant.class, new ValueSerializer<Instant>() {
                /**
                 * 按共享时间格式向 JSON 生成器写入值，使各服务的时间字段保持相同对外表示。
                 */
                @Override
                public void serialize(Instant value,
                                      JsonGenerator generator,
                                      SerializationContext serializationContext) {
                    generator.writeString(FLOWDESK_INSTANT.format(value));
                }
            });
            builder.addModule(module);
        };
    }
}
