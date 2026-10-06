package com.example.demo.repository;

import com.example.demo.entity.DiagnosisAnswer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 診断の回答を保存し、診断1回分の回答を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DiagnosisAnswerRepository extends JpaRepository<DiagnosisAnswer, Long> {
    // 指定した診断1回分の回答を取得するためのメソッド。現在の集計処理では使っていない。
    List<DiagnosisAnswer> findBySessionId(Long sessionId);
}
