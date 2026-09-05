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
 * 地酒本体（sake）。
 * 要件定義書 5.3 では「DBスキーマ確定前は@Entityを付与しない」方針だったが、
 * newbooze.sql でスキーマが確定したため、ここで正式に @Entity 化する。
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
