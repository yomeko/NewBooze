package com.example.demo.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** BCryptの上限を、全角文字も含めてハッシュ化前に確認する。 */
public class Utf8ByteLengthValidator implements ConstraintValidator<Utf8ByteLength, CharSequence> {
    private int max;

    @Override
    public void initialize(Utf8ByteLength constraint) {
        max = constraint.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        // 必須入力は@NotBlankに任せ、同じ原因によるエラーの重複を避ける。
        return Utf8InputLength.isAtMost(value, max);
    }
}
