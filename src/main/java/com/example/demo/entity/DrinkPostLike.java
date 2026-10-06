package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * データベースのdrink_post_likesという表の1件を、Javaで扱うためのクラス。
 * 誰がどの投稿に「いいね」を付けたかを持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 * 2つのIDの組み合わせで1件を区別する。@MapsIdで関連先のIDと組み合わせのIDをそろえる。
 */
@Entity @Table(name = "drink_post_likes") @Getter @Setter @NoArgsConstructor
public class DrinkPostLike {
    @EmbeddedId private DrinkPostLikeId id = new DrinkPostLikeId();
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("userId") @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("postId") @JoinColumn(name = "post_id") private DrinkPost post;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;
}
