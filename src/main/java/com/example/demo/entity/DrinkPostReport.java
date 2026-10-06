package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * データベースのdrink_post_reportsという表の1件を、Javaで扱うためのクラス。
 * 誰がどの投稿を、どの理由で通報したかを持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 * 同じ人が同じ投稿を重複して通報しないよう、DB側にも組み合わせの重複禁止を指定する。
 */
@Entity @Table(name = "drink_post_reports", uniqueConstraints = @UniqueConstraint(columnNames={"reporter_id","post_id"}))
@Getter @Setter @NoArgsConstructor
public class DrinkPostReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reporter_id", nullable = false) private User reporter;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "post_id", nullable = false) private DrinkPost post;
    @Column(nullable = false, length = 30) private String reason;
    @Column(name = "created_at", insertable = false, updatable = false) private LocalDateTime createdAt;
}
