package com.example.demo.repository;

import com.example.demo.entity.DrinkPostReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrinkPostReportRepository extends JpaRepository<DrinkPostReport, Long> {
    boolean existsByReporterIdAndPostId(Long reporterId, Long postId);
}
