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
 * 地酒タグ中間テーブル（sake_tags）。レコメンドの特徴ベクトルの元データ。
 *
 * 中間テーブルに score のような「関連そのものが持つ属性」がある場合、
 * 単純な @ManyToMany では表現できないため、中間テーブル自体を Entity 化し、
 * 複合PKを @EmbeddedId + @MapsId で扱う（内部設計書 メモ：型安全に扱うための採用理由）。
 *
 * @MapsId により、sake / tag への外部キー値が SakeTagId 内の sakeId / tagId と自動的に同期する。
 */
@Entity
@Table(name = "sake_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SakeTag {

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

    /** 当該地酒における当該タグの強さ（1〜5想定）。デフォルト3。 */
    @Column(nullable = false)
    private Integer score = 3;
}
