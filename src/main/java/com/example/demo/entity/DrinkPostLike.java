package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "drink_post_likes") @Getter @Setter @NoArgsConstructor
public class DrinkPostLike {
    @EmbeddedId private DrinkPostLikeId id = new DrinkPostLikeId();
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("userId") @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("postId") @JoinColumn(name = "post_id") private DrinkPost post;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;
}
