package com.example.demo.repository;

import com.example.demo.entity.DiagnosisSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 診断1回分の記録を保存し、ユーザーごとの記録を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DiagnosisSessionRepository extends JpaRepository<DiagnosisSession, Long> {
    // ユーザーの診断履歴を新しい順に取得する。現在のマイページでは履歴一覧を表示していない。
    List<DiagnosisSession> findByUserIdOrderByTakenAtDesc(Long userId);
}
