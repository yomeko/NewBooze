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
 * データベースのchoice_tagsという表の1件を、Javaで扱うためのクラス。
 * 診断の選択肢と特徴の対応、および選んだときの加点を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 * 2つのIDの組み合わせで1件を区別する。@MapsIdで関連先のIDと組み合わせのIDをそろえる。
 */
@Entity
@Table(name = "choice_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChoiceTag {

    // 選択肢IDと特徴IDをまとめた識別子。例：選択肢10と「甘口」の組み合わせ。
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

    /** 選択時にタグへ与える重み。デフォルト1。DB側がTINYINTのためByte型にマッピング */
    @Column(nullable = false)
    private Byte weight = 1;
}
