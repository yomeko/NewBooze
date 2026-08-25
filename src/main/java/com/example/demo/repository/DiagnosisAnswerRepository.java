package com.example.demo.repository;

import com.example.demo.entity.DiagnosisAnswer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisAnswerRepository extends JpaRepository<DiagnosisAnswer, Long> {
    // セッション終了時、集計対象の回答一覧を取得する（DiagnosisService から利用）
    List<DiagnosisAnswer> findBySessionId(Long sessionId);
}
