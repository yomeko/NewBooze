package com.example.demo.repository;

import com.example.demo.entity.DrinkPostLike;
import com.example.demo.entity.DrinkPostLikeId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrinkPostLikeRepository extends JpaRepository<DrinkPostLike, DrinkPostLikeId> {
    long countByIdPostId(Long postId);
    List<DrinkPostLike> findByIdUserIdAndIdPostIdIn(Long userId, Collection<Long> postIds);
}
