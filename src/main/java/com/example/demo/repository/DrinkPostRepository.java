package com.example.demo.repository;

import com.example.demo.entity.DrinkPost;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 飲酒投稿を保存・検索し、重複や短時間の投稿件数を調べるためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DrinkPostRepository extends JpaRepository<DrinkPost, Long> {
    // ページに必要な投稿者を一緒に読み、テンプレートから追加でDBを読まない。
    @EntityGraph(attributePaths = "user")
    Page<DrinkPost> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DrinkPost p where p.id = :id")
    Optional<DrinkPost> findForUpdate(Long id);

    // 投稿・いいね・通報を、既存の外部キーの連動削除でまとめて消す。
    @Modifying(clearAutomatically = true)
    @Query("delete from DrinkPost p where p.id = :id")
    void deleteWithActivity(Long id);

    // DBが記録した投稿日時と同じ時計を使い、旧データのNULLも空欄と同じ内容として比較する。
    @Query(value = """
            select count(*) from drink_posts
            where user_id = :userId and lower(sake_name) = lower(:sakeName)
            and coalesce(comment, '') = :comment and created_at > current_timestamp - interval 24 hour
            """, nativeQuery = true)
    long countRecentDuplicates(Long userId, String sakeName, String comment);

    @Query(value = """
            select count(*) from drink_posts
            where user_id = :userId and created_at > current_timestamp - interval 10 minute
            """, nativeQuery = true)
    long countRecentPosts(Long userId);
}
