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
 * データベースのdiagnosis_questionsという表の1件を、Javaで扱うためのクラス。
 * 診断の質問文と表示順を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 */
@Entity
@Table(name = "diagnosis_questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_text", nullable = false, length = 255)
    private String questionText;

    /** 画面表示順。S02では sort_order 順に1問ずつ表示する（外部設計書 3.3 S02）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
