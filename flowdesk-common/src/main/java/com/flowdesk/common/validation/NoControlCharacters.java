package com.flowdesk.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 拒绝 NUL 及非必要的 ISO 控制字符；保留制表符、换行和回车，以支持工单描述等多行文本。
 */
@Documented
@Constraint(validatedBy = NoControlCharactersValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoControlCharacters {

    /**
     * 定义校验失败的默认提示，供 Bean Validation 输出字段错误。
     */
    String message() default "字符串包含不允许的控制字符。";

    /**
     * 指定此约束参与的校验分组，默认遵循标准分组规则。
     */
    Class<?>[] groups() default {};

    /**
     * 声明附加校验元信息类型，遵循 Bean Validation 注解契约。
     */
    Class<? extends Payload>[] payload() default {};
}
