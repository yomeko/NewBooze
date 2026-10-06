package com.example.demo.repository;

import com.example.demo.entity.DiagnosisQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 診断の質問を、表示する順番で取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DiagnosisQuestionRepository extends JpaRepository<DiagnosisQuestion, Long> {
    // S02は sort_order 順に1問ずつ出題する仕様（外部設計書 3.3 S02）
    List<DiagnosisQuestion> findAllByOrderBySortOrderAsc();
}
