package com.example.demo.repository;

import com.example.demo.entity.DiagnosisSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 診断1回分の記録を保存し、ユーザーごとの記録を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DiagnosisSessionRepository extends JpaRepository<DiagnosisSession, Long> {
    // 同じ秒に複数回保存された場合も、ページをまたいで表示順がぶれないようIDで並べる。
    Slice<DiagnosisSession> findByUserIdOrderByTakenAtDescIdDesc(Long userId, Pageable pageable);
}
