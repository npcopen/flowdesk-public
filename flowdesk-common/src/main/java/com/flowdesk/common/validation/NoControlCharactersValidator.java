package com.flowdesk.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link NoControlCharacters} 的约束校验实现。
 */
public final class NoControlCharactersValidator
        implements ConstraintValidator<NoControlCharacters, CharSequence> {

    private static final int HTAB = 0x09;
    private static final int LF = 0x0A;
    private static final int CR = 0x0D;

    /**
     * 按 Unicode 码点扫描文本，允许制表符及换行符，拒绝其他 ISO 控制字符；null 交由独立非空约束处理。
     */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); ) {
            int codePoint = Character.codePointAt(value, i);
            if (codePoint != HTAB && codePoint != LF && codePoint != CR
                    && Character.isISOControl(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }
}
