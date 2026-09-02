package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable @Getter @Setter @NoArgsConstructor
public class DrinkPostLikeId implements Serializable {
    @Column(name = "user_id") private Long userId;
    @Column(name = "post_id") private Long postId;
    public DrinkPostLikeId(Long userId, Long postId) { this.userId = userId; this.postId = postId; }
    @Override public boolean equals(Object o) { return o instanceof DrinkPostLikeId id && Objects.equals(userId,id.userId) && Objects.equals(postId,id.postId); }
    @Override public int hashCode() { return Objects.hash(userId, postId); }
}
