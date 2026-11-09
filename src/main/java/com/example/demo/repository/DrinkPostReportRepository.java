package com.example.demo.repository;

import com.example.demo.entity.DrinkPostReport;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * 投稿への通報を保存し、同じユーザーがすでに通報したかを調べるためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DrinkPostReportRepository extends JpaRepository<DrinkPostReport, Long> {
    // 指定したユーザーが、指定した投稿をすでに通報したかを調べる。
    boolean existsByReporterIdAndPostId(Long reporterId, Long postId);

    @Query("""
            select r.post.id from DrinkPostReport r
            where r.reporter.id = :userId and r.post.id in :postIds
            """)
    List<Long> findReportedPostIds(Long userId, Collection<Long> postIds);

    @EntityGraph(attributePaths = {"post", "post.user"})
    Page<DrinkPostReport> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    @Query("select r.post.id from DrinkPostReport r where r.id = :id")
    Optional<Long> findPostId(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DrinkPostReport r where r.id = :id")
    Optional<DrinkPostReport> findForUpdate(Long id);
}
