package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * drink_post_likesの1件を区別する、ユーザーIDと投稿IDの組み合わせ。
 * 複合主キーとは、1つの番号だけではなく複数の値を合わせて使う識別子のこと。
 * equalsは両方のIDが同じかを確認し、hashCodeも両方から計算して同じ組み合わせを扱えるようにする。
 */
@Embeddable @Getter @Setter @NoArgsConstructor
public class DrinkPostLikeId implements Serializable {
    @Column(name = "user_id") private Long userId;
    @Column(name = "post_id") private Long postId;
    public DrinkPostLikeId(Long userId, Long postId) { this.userId = userId; this.postId = postId; }
    @Override public boolean equals(Object o) { return o instanceof DrinkPostLikeId id && Objects.equals(userId,id.userId) && Objects.equals(postId,id.postId); }
    @Override public int hashCode() { return Objects.hash(userId, postId); }
}
