package com.example.demo.repository;

import com.example.demo.entity.Favorite;
import com.example.demo.entity.FavoriteId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavoriteRepository extends JpaRepository<Favorite, FavoriteId> {
    List<Favorite> findByIdUserId(Long userId);

    boolean existsByIdUserIdAndIdSakeId(Long userId, Long sakeId);

    void deleteByIdUserIdAndIdSakeId(Long userId, Long sakeId);
}
