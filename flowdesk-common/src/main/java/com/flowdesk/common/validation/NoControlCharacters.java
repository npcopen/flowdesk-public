package com.flowdesk.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects NUL and non-necessary ISO control characters in validated strings.
 *
 * <p>API-SPEC §2.8 requires every accepted string to reject NUL and control characters
 * that have no protocol necessity. Horizontal tab, LF and CR remain allowed so text
 * values such as ticket descriptions are not silently rewritten; NUL, C1 controls and
 * all other C0 controls are rejected before any business use.</p>
 */
@Documented
@Constraint(validatedBy = NoControlCharactersValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoControlCharacters {

    String message() default "字符串包含不允许的控制字符。";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
