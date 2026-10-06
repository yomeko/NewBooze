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
 * データベースのtagsという表の1件を、Javaで扱うためのクラス。
 * 「甘口」「軽快」などの特徴名と分類を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
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
