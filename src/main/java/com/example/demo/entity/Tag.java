package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * タグマスタ（tags）。
 * 診断選択肢（choice_tags）と地酒（sake_tags）の双方から参照される、
 * レコメンドロジックの軸となるテーブル。外部設計書 5.2 参照。
 */
@Entity
@Table(name = "tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /** タグ分類（味わい／香り／タイプ等）。 */
    @Column(nullable = false, length = 30)
    private String category;
}
