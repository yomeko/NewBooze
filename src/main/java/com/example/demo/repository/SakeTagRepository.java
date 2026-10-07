package com.example.demo.repository;

import com.example.demo.entity.SakeTag;
import com.example.demo.entity.SakeTagId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

/**
 * 日本酒に付いた特徴と、その特徴の強さを取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface SakeTagRepository extends JpaRepository<SakeTag, SakeTagId> {
    // 実際に銘柄へ付いている特徴だけを、検索プルダウンの選択肢にする。
    @Query("SELECT DISTINCT st.tag.name FROM SakeTag st WHERE st.score > 0 ORDER BY st.tag.name")
    List<String> findDistinctPositiveTagNames();

    // 1銘柄の特徴名と点数を取得する。@EntityGraphで関連するtagも一緒に読む。
    @EntityGraph(attributePaths = "tag")
    List<SakeTag> findByIdSakeId(Long sakeId);

    // 複数銘柄の特徴をまとめて読む。銘柄ごとに何度もDBへ問い合わせる回数を減らす。
    @EntityGraph(attributePaths = "tag")
    List<SakeTag> findByIdSakeIdIn(List<Long> sakeIds);
}
