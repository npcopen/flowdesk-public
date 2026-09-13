package com.flowdesk.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validator implementation for {@link NoControlCharacters}. */
public final class NoControlCharactersValidator
        implements ConstraintValidator<NoControlCharacters, CharSequence> {

    private static final int HTAB = 0x09;
    private static final int LF = 0x0A;
    private static final int CR = 0x0D;

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
