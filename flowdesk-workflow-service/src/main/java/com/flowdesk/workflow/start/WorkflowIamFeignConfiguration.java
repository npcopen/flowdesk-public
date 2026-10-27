package com.flowdesk.workflow.start;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 仅由 WorkflowIamEligibilityFeignClient 引入的配置，不标记 Configuration 或 Component，
 * 避免组件扫描将其注册为其他 Feign 客户端的全局策略。
 *
 * <p>沿用连接两秒、读取三秒的可配置超时；禁用重试、请求日志和重定向，
 * 不把一次资格预检扩成多次调用，也不把服务令牌记录或转发到其他地址。
 */
public class WorkflowIamFeignConfiguration {

    /**
     * 装配远程请求超时配置，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    public Request.Options workflowIamRequestOptions(WorkflowIamClientProperties properties) {
        return new Request.Options(
                properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS, false);
    }

    /**
     * 装配远程重试策略，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    public Retryer workflowIamRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 装配远程调用日志级别，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    public Logger.Level workflowIamLoggerLevel() {
        return Logger.Level.NONE;
    }
}
