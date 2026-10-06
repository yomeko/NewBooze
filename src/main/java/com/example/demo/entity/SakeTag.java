package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * データベースのsake_tagsという表の1件を、Javaで扱うためのクラス。
 * 日本酒と特徴の対応、およびその特徴の強さを持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 * 2つのIDの組み合わせで1件を区別する。@MapsIdで関連先のIDと組み合わせのIDをそろえる。
 */
@Entity
@Table(name = "sake_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SakeTag {

    // 銘柄IDと特徴IDの組み合わせで、どの銘柄のどの特徴かを識別する。
    @EmbeddedId
    private SakeTagId id = new SakeTagId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("sakeId") // SakeTagId.sakeId とこの関連の外部キーを紐付ける
    @JoinColumn(name = "sake_id")
    private Sake sake;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("tagId")
    @JoinColumn(name = "tag_id")
    private Tag tag;
    
    /** 当該地酒における当該タグの強さ（1〜5想定）。デフォルト3。DB側がTINYINTのためByte型にマッピング */
    @Column(nullable = false)
    private Byte score = 3;
}
