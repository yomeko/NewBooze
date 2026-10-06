package com.example.demo.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * user_preferencesの1件を区別する、ユーザーIDと特徴IDの組み合わせ。
 * 複合主キーとは、1つの番号だけではなく複数の値を合わせて使う識別子のこと。
 * equalsは両方のIDが同じかを確認し、hashCodeも両方から計算して同じ組み合わせを扱えるようにする。
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceId implements Serializable {

    private Long userId;
    private Long tagId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserPreferenceId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tagId);
    }
}
