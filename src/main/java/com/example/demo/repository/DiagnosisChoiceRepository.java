package com.example.demo.repository;

import com.example.demo.entity.DiagnosisChoice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 質問に属する選択肢や、IDに対応する選択肢を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DiagnosisChoiceRepository extends JpaRepository<DiagnosisChoice, Long> {
    // 指定した質問IDに属する選択肢を取り出す。QuestionIdは関連先questionのidを指す。
    List<DiagnosisChoice> findByQuestionId(Long questionId);
}
