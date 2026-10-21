package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PublicationInformationTests {
    @Test void unsetInformationRemainsUnconfigured() {
        var info = new PublicationInformation(" ", "");
        assertThat(info.isOperatorConfigured()).isFalse();
        assertThat(info.isContactConfigured()).isFalse();
    }

    @Test void validInformationIsTrimmedAndAvailable() {
        var info = new PublicationInformation(" 研究担当 ", " contact+research@example.com ");
        assertThat(info.getOperatorName()).isEqualTo("研究担当");
        assertThat(info.getContactEmail()).isEqualTo("contact+research@example.com");
        assertThat(info.isOperatorConfigured()).isTrue();
        assertThat(info.isContactConfigured()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-address", "a@example.com?bcc=someone@example.com",
            "a%0D%0A@example.com", "a@example.com\nBcc:other@example.com", "a..b@example.com"})
    void rejectsInvalidOrMailtoHeaderInput(String email) {
        assertThatThrownBy(() -> new PublicationInformation("研究担当", email))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("公開用の問い合わせメールアドレス設定が不正です。");
    }

    @Test void rejectsMultilineOrExcessiveOperatorName() {
        assertThatThrownBy(() -> new PublicationInformation("担当\n者", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicationInformation("あ".repeat(121), ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
