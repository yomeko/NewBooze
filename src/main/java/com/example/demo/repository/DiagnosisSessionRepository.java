package com.example.demo.repository;

import com.example.demo.entity.DiagnosisSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisSessionRepository extends JpaRepository<DiagnosisSession, Long> {
    // S08マイページでの過去の診断履歴確認に使用
    List<DiagnosisSession> findByUserIdOrderByTakenAtDesc(Long userId);
}
