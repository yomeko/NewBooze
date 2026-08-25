package com.example.demo.entity;

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
 * 診断回答（diagnosis_answers）。
 * newbooze.sql の制約定義より、session_id は ON DELETE CASCADE、
 * question_id・choice_id はデフォルト(RESTRICT)であるため、
 * 設問・選択肢自体は回答が残っている限り削除できない点に注意。
 */
@Entity
@Table(name = "diagnosis_answers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private DiagnosisSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private DiagnosisQuestion question;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "choice_id", nullable = false)
    private DiagnosisChoice choice;
}
