package com.example.demo.validation;

import java.nio.charset.StandardCharsets;

/** 登録フォームと設定画面で同じUTF-8バイト上限を使うための共通処理。 */
public final class Utf8InputLength {
    private Utf8InputLength() {}

    public static boolean isAtMost(CharSequence value, int max) {
        return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
