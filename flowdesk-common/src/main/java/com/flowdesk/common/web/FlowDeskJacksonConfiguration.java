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
 * Shared Jackson 3 policy for FlowDesk MVC services.
 *
 * <p>API-SPEC requires duplicate JSON keys to be rejected and every UTC timestamp in a
 * response to use exactly six fractional digits followed by {@code Z}. The Jackson 3
 * default emits either zero or nine fractional digits, so a fixed formatter is
 * registered here together with strict duplicate-key detection.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(JsonMapper.class)
public class FlowDeskJacksonConfiguration {

    private static final DateTimeFormatter FLOWDESK_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    @Bean
    public JsonMapperBuilderCustomizer flowDeskJsonMapperBuilderCustomizer() {
        return builder -> {
            builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
            builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            SimpleModule module = new SimpleModule("flowdesk-instant-format");
            module.addSerializer(Instant.class, new ValueSerializer<Instant>() {
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
