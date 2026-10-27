package com.flowdesk.common.web;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.JwtVerifier;
import com.flowdesk.common.security.PemKeyMaterial;
import tools.jackson.databind.ObjectMapper;
import com.flowdesk.common.security.PemKeyReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;

/**
 * MVC 业务服务共用的 Servlet 安全适配配置。
 * 安装追踪标识规范化、人类 JWT 本地复验、可选的服务 JWT 认证和权限拦截；缺少验签密钥时启动失败。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(FlowDeskJwtProperties.class)
public class FlowDeskWebSecurityConfiguration {

    /**
     * 解析配置公钥，必要时从 RSA CRT 私钥派生公钥，再创建带签发方、受众及时钟偏差的验证器。
     */
    @Bean
    public JwtVerifier flowDeskJwtVerifier(FlowDeskJwtProperties properties,
                                           ResourceLoader resourceLoader) {
        PublicKey publicKey;
        String publicPem = PemKeyMaterial.resolve(
                properties.getPublicKeyPem(), properties.getPublicKeyLocation(), resourceLoader);
        String privatePem = PemKeyMaterial.resolve(
                properties.getPrivateKeyPem(), properties.getPrivateKeyLocation(), resourceLoader);
        if (!publicPem.isBlank()) {
            publicKey = PemKeyReader.readPublicKey(publicPem);
        } else if (!privatePem.isBlank()) {
            publicKey = publicKeyFromPrivate(PemKeyReader.readPrivateKey(privatePem));
        } else {
            throw new IllegalStateException(
                    "FlowDesk JWT verification key is not configured (flowdesk.security.jwt.public-key-pem/public-key-location or private-key-pem/private-key-location)");
        }
        return new JwtVerifier(publicKey,
                properties.getIssuer(),
                properties.getHumanAudience(),
                properties.getServiceAudience(),
                properties.getClockSkew().toSeconds());
    }

    /**
     * 高优先级注册关联标识过滤器，使后续认证和业务响应共享追踪标识。
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(
            FlowDeskJwtProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationIdFilter(
                properties.getStrictCorrelationPaths(), objectMapperProvider.getIfAvailable(ObjectMapper::new)));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * 在业务处理前注册请求体策略，统一约束写入大小及内容编码。
     */
    @Bean
    public FilterRegistrationBean<FlowDeskRequestBodyPolicyFilter> requestBodyPolicyFilterRegistration(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        FilterRegistrationBean<FlowDeskRequestBodyPolicyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new FlowDeskRequestBodyPolicyFilter(
                objectMapperProvider.getIfAvailable(ObjectMapper::new)));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        return registration;
    }

    /**
     * 注册人类 JWT 过滤器，注入公开路径、服务路径和统一错误映射器。
     */
    @Bean
    public FilterRegistrationBean<HumanJwtAuthenticationFilter> humanJwtAuthenticationFilterRegistration(
            FlowDeskJwtProperties properties,
            JwtVerifier verifier,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        FilterRegistrationBean<HumanJwtAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HumanJwtAuthenticationFilter(
                verifier, properties.getPublicPaths(), properties.getServiceJwtPaths(),
                objectMapperProvider.getIfAvailable(ObjectMapper::new)));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /**
     * 注册服务 JWT 过滤器，限定内部路径及允许调用的服务身份。
     */
    @Bean
    public FilterRegistrationBean<ServiceJwtAuthenticationFilter> serviceJwtAuthenticationFilterRegistration(
            FlowDeskJwtProperties properties,
            JwtVerifier verifier,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        FilterRegistrationBean<ServiceJwtAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ServiceJwtAuthenticationFilter(
                verifier, properties.getServiceJwtPaths(), properties.getAllowedServiceIds(),
                objectMapperProvider.getIfAvailable(ObjectMapper::new)));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }

    /**
     * 创建权限注解拦截器，在进入控制器前检查已验证主体的有效权限。
     */
    @Bean
    public PermissionInterceptor flowDeskPermissionInterceptor() {
        return new PermissionInterceptor();
    }

    /**
     * 把权限拦截器加入 MVC 处理链，统一执行类或方法上的权限声明。
     */
    @Bean
    public WebMvcConfigurer flowDeskPermissionWebMvcConfigurer(PermissionInterceptor interceptor) {
        return new WebMvcConfigurer() {
            /**
             * 把权限拦截器注册到 MVC 链，确保控制器权限注解在调用前生效。
             */
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    /**
     * 从 RSA CRT 私钥提取模数和公钥指数派生公钥，类型不支持或派生失败时阻止启动。
     */
    private static PublicKey publicKeyFromPrivate(PrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException(
                    "Only RSA CRT private keys can be used without a separate public key");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive RSA public key from private key", e);
        }
    }
}
