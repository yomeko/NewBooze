package com.example.demo.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Column;

/**
 * データベースのfavoritesという表の1件を、Javaで扱うためのクラス。
 * 誰がどの日本酒をお気に入りに登録したかを持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 * 2つのIDの組み合わせで1件を区別する。@MapsIdで関連先のIDと組み合わせのIDをそろえる。
 */
@Entity
@Table(name = "favorites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Favorite {

    // ユーザーIDと日本酒IDの組み合わせなので、同じ人の同じ銘柄は1件として扱う。
    @EmbeddedId
    private FavoriteId id = new FavoriteId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("sakeId")
    @JoinColumn(name = "sake_id")
    private Sake sake;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
