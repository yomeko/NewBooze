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
 * 診断選択肢タグ中間テーブル（choice_tags）。
 * 診断で選択肢を選ぶと、ここに紐づく tag へ weight 分のスコアが加算される
 * （DiagnosisService の集計ロジックの入力データ）。
 */
@Entity
@Table(name = "choice_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChoiceTag {

    @EmbeddedId
    private ChoiceTagId id = new ChoiceTagId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("choiceId")
    @JoinColumn(name = "choice_id")
    private DiagnosisChoice choice;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("tagId")
    @JoinColumn(name = "tag_id")
    private Tag tag;

    /** 選択時にタグへ与える重み。デフォルト1。 */
    @Column(nullable = false)
    private Integer weight = 1;
}
