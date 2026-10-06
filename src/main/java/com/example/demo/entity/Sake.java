package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * データベースのsakeという表の1件を、Javaで扱うためのクラス。
 * 日本酒の名前、酒蔵、酒種、産地、度数、価格などの基本情報を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 */
@Entity
@Table(name = "sake")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Sake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 公開レビューだけから平均の星の数を計算する。レビューがなければnullになる。
    @org.hibernate.annotations.Formula("(select avg(r.rating) from sake_reviews r where r.sake_id = id and r.published = 1)")
    private Double averageRating;

    // 公開レビューの件数。DBの専用列に保存せず、銘柄を読むときに計算する。
    @org.hibernate.annotations.Formula("(select count(*) from sake_reviews r where r.sake_id = id and r.published = 1)")
    private Long reviewCount;

    // レビューの有無を並び替えに使う。公開レビューがあれば1、なければ0。
    @org.hibernate.annotations.Formula("(case when exists (select 1 from sake_reviews r where r.sake_id = id and r.published = 1) then 1 else 0 end)")
    private Integer hasPublicReviews;

    /** 蔵元。新SQLでは未登録の銘柄も許容されるためnullable。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brewery_id")
    private Brewery brewery;

    // FetchType.LAZY: 検索一覧表示のたびに酒種情報をJOINで引く必要がない場面もあるため、
    // 必要な時だけ取得するようにして無駄なクエリを避ける。
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sake_type_id", nullable = false)
    private SakeType sakeType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String region;

    /** アルコール度数(%)。DECIMAL(4,1) に合わせて precision=4, scale=1。 */
    @Column(precision = 4, scale = 1)
    private BigDecimal abv;

    /** 価格(円)。 */
    private Integer price;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 255)
    private String imageUrl;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
