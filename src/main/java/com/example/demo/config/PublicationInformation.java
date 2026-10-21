package com.example.demo.config;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 公開用の運営者情報。開発時の未設定と、設定済みの不正値を区別する。 */
@Component
public class PublicationInformation {
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+");
    private final String operatorName;
    private final String contactEmail;

    public PublicationInformation(@Value("${app.public.operator-name:}") String operatorName,
            @Value("${app.public.contact-email:}") String contactEmail) {
        this.operatorName = operatorName == null ? "" : operatorName.strip();
        this.contactEmail = contactEmail == null ? "" : contactEmail.strip();
        if (this.operatorName.length() > 120 || this.operatorName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("公開用の運営者名は改行を含まない120文字以内で設定してください。");
        }
        if (!this.contactEmail.isEmpty() && (this.contactEmail.length() > 254
                || !EMAIL.matcher(this.contactEmail).matches()
                || this.contactEmail.startsWith(".") || this.contactEmail.contains("..")
                || this.contactEmail.contains(".@"))) {
            // 設定値自体は例外・ログへ出力しない。
            throw new IllegalStateException("公開用の問い合わせメールアドレス設定が不正です。");
        }
    }

    public String getOperatorName() { return operatorName; }
    public String getContactEmail() { return contactEmail; }
    public boolean isOperatorConfigured() { return !operatorName.isEmpty(); }
    public boolean isContactConfigured() { return !contactEmail.isEmpty(); }
}
