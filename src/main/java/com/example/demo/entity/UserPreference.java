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
 * データベースのuser_preferencesという表の1件を、Javaで扱うためのクラス。
 * ユーザーと特徴の対応、および診断で得た好みの点数を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 * 2つのIDの組み合わせで1件を区別する。@MapsIdで関連先のIDと組み合わせのIDをそろえる。
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserPreference {

    @EmbeddedId
    private UserPreferenceId id = new UserPreferenceId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("tagId")
    @JoinColumn(name = "tag_id")
    private Tag tag;

    // そのユーザーがこの特徴をどれだけ好むかを表す、診断回答からの合計点。
    @Column(nullable = false)
    private Integer score = 0;
}
