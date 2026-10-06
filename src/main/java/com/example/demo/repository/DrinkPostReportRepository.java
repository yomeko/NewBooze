package com.example.demo.repository;

import com.example.demo.entity.DrinkPostReport;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 投稿への通報を保存し、同じユーザーがすでに通報したかを調べるためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DrinkPostReportRepository extends JpaRepository<DrinkPostReport, Long> {
    // 指定したユーザーが、指定した投稿をすでに通報したかを調べる。
    boolean existsByReporterIdAndPostId(Long reporterId, Long postId);
}
