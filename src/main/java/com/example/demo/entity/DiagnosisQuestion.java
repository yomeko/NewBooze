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
 * 診断設問マスタ（diagnosis_questions）。
 * 既存の com.example.demo.model.DiagnosisQuestion（record・インメモリ用）とは別物。
 * こちらはDB永続化用のJPA Entityであり、パッケージが異なるため名前が重複してもコンパイルは通る。
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
