package com.example.demo.repository;

import com.example.demo.entity.DiagnosisQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisQuestionRepository extends JpaRepository<DiagnosisQuestion, Long> {
    // S02は sort_order 順に1問ずつ出題する仕様（外部設計書 3.3 S02）
    List<DiagnosisQuestion> findAllByOrderBySortOrderAsc();
}
