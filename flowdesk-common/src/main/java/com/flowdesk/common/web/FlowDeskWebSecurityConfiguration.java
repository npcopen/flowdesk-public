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
 * Servlet security adapters shared by all MVC fact services.
 *
 * <p>Installs correlation normalization, local human JWT re-verification, optional
 * service JWT paths and permission interception. Key material is required: missing
 * verification material fails startup instead of defaulting to an insecure mode.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(FlowDeskJwtProperties.class)
public class FlowDeskWebSecurityConfiguration {

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

    @Bean
    public PermissionInterceptor flowDeskPermissionInterceptor() {
        return new PermissionInterceptor();
    }

    @Bean
    public WebMvcConfigurer flowDeskPermissionWebMvcConfigurer(PermissionInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

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
