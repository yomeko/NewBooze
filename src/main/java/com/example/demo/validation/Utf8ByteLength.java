package com.example.demo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 文字数とは別に、UTF-8に変換したときの入力サイズを検証する。 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Utf8ByteLengthValidator.class)
public @interface Utf8ByteLength {
    String message() default "UTF-8で{max}バイト以内で入力してください";
    int max();
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
