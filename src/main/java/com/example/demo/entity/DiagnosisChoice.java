package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 診断選択肢（diagnosis_choices）。
 * question_id → diagnosis_questions.id は ON DELETE CASCADE のため、
 * 設問が削除されれば選択肢も自動的に削除される。
 */
@Entity
@Table(name = "diagnosis_choices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private DiagnosisQuestion question;

    @Column(name = "choice_text", nullable = false, length = 255)
    private String choiceText;
}
