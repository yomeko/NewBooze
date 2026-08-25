package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Column;

/**
 * 診断セッション（diagnosis_sessions）。
 * ユーザー1回分の診断実施単位。DiagnosisController の userId 暫定処理は、
 * S07（ログイン機能）実装後にここへ差し替える（内部設計書 第8章「今後の課題」）。
 */
@Entity
@Table(name = "diagnosis_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "taken_at", insertable = false, updatable = false)
    private LocalDateTime takenAt;
}
