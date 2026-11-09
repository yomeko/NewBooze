package com.example.demo.repository;

import com.example.demo.entity.DrinkPostLike;
import com.example.demo.entity.DrinkPostLikeId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 投稿への「いいね」を保存し、件数や本人の登録状況を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DrinkPostLikeRepository extends JpaRepository<DrinkPostLike, DrinkPostLikeId> {
    interface LikeCount {
        Long getPostId();
        long getTotal();
    }

    @Query("""
            select l.id.postId as postId, count(l) as total from DrinkPostLike l
            where l.id.postId in :postIds group by l.id.postId
            """)
    List<LikeCount> countForPosts(Collection<Long> postIds);

    @Query("""
            select l.id.postId from DrinkPostLike l
            where l.id.userId = :userId and l.id.postId in :postIds
            """)
    List<Long> findLikedPostIds(Long userId, Collection<Long> postIds);
}
