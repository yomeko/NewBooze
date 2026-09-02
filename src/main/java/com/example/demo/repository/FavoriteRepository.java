package com.example.demo.repository;

import com.example.demo.entity.Favorite;
import com.example.demo.entity.FavoriteId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface FavoriteRepository extends JpaRepository<Favorite, FavoriteId> {
    @EntityGraph(attributePaths = "sake")
    List<Favorite> findByIdUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByIdUserIdAndIdSakeId(Long userId, Long sakeId);

    void deleteByIdUserIdAndIdSakeId(Long userId, Long sakeId);
}
