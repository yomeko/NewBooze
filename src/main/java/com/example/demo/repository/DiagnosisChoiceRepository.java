package com.example.demo.repository;

import com.example.demo.entity.DiagnosisChoice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisChoiceRepository extends JpaRepository<DiagnosisChoice, Long> {
    List<DiagnosisChoice> findByQuestionId(Long questionId);
}
