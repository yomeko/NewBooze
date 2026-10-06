package com.example.demo.repository;

import com.example.demo.entity.Favorite;
import com.example.demo.entity.FavoriteId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

/**
 * お気に入りを保存・削除し、登録済みかどうかやユーザーごとの一覧を調べるためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface FavoriteRepository extends JpaRepository<Favorite, FavoriteId> {
    // 一覧で銘柄名を使うため、お気に入りと関連するsakeの情報を一緒に読み込む。
    @EntityGraph(attributePaths = "sake")
    List<Favorite> findByIdUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByIdUserIdAndIdSakeId(Long userId, Long sakeId);

    void deleteByIdUserIdAndIdSakeId(Long userId, Long sakeId);
}
