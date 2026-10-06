package com.example.demo.repository;

import com.example.demo.entity.DrinkPost;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 飲酒投稿を保存・検索し、重複や短時間の投稿件数を調べるためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DrinkPostRepository extends JpaRepository<DrinkPost, Long> {
    List<DrinkPost> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 全投稿を新しい順に読み、画面表示に使えるよう投稿者の情報も一緒に取得する。
    @EntityGraph(attributePaths = "user")
    List<DrinkPost> findAllByOrderByCreatedAtDesc();

    // 同じユーザー・銘柄名・感想で、指定した日時より後の投稿があるか調べる。
    boolean existsByUserIdAndSakeNameIgnoreCaseAndCommentAndCreatedAtAfter(
            Long userId, String sakeName, String comment, LocalDateTime after);

    // 指定したユーザーが、指定した日時より後に行った投稿数を数える。
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);
}
