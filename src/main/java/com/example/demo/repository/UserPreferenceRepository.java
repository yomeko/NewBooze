package com.example.demo.repository;

import com.example.demo.entity.UserPreference;
import com.example.demo.entity.UserPreferenceId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ユーザーごとの好みの点数を保存・検索するためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface UserPreferenceRepository extends JpaRepository<UserPreference, UserPreferenceId> {
    // 指定ユーザーの好みの点数一覧を取得するためのメソッド。
    List<UserPreference> findByIdUserId(Long userId);

    // マイページ用に点数の高い順で取得する。join fetchで、表示する特徴名の情報も同時に読む。
    @org.springframework.data.jpa.repository.Query("select p from UserPreference p join fetch p.tag where p.id.userId = :userId order by p.score desc")
    List<UserPreference> findByIdUserIdOrderByScoreDesc(@org.springframework.data.repository.query.Param("userId") Long userId);

    // upsert（存在すれば更新、無ければ新規作成）判定のための単一取得
    Optional<UserPreference> findByIdUserIdAndIdTagId(Long userId, Long tagId);
}
