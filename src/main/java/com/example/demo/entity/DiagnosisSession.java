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
 * データベースのdiagnosis_sessionsという表の1件を、Javaで扱うためのクラス。
 * ユーザーが行った1回分の診断と実施時刻を持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
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

    // 実施日時はDBが設定する。insertable/updatable=falseでJavaからは書き込まない。
    @Column(name = "taken_at", insertable = false, updatable = false)
    private LocalDateTime takenAt;
}
