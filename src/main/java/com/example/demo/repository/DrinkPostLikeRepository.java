package com.example.demo.repository;

import com.example.demo.entity.DrinkPostLike;
import com.example.demo.entity.DrinkPostLikeId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 投稿への「いいね」を保存し、件数や本人の登録状況を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface DrinkPostLikeRepository extends JpaRepository<DrinkPostLike, DrinkPostLikeId> {
    // 1投稿のいいね件数を数える。次のメソッドは複数投稿への本人のいいねをまとめて調べる。
    long countByIdPostId(Long postId);
    List<DrinkPostLike> findByIdUserIdAndIdPostIdIn(Long userId, Collection<Long> postIds);
}
