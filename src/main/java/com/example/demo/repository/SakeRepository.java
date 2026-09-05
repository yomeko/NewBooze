package com.example.demo.repository;

import com.example.demo.entity.Sake;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SakeRepository extends JpaRepository<Sake, Long> {

    /**
     * S04（検索結果一覧画面）向けの多条件検索。
     * 外部設計書 4.4：キーワード（name部分一致）／sake_type_id／region で絞り込み、
     * ページネーション情報付きで返す。
     *
     * JPQLでは「未入力の条件はスキップする」動的検索を、
     * "(:param IS NULL OR 条件)" という書き方で表現できる。
     * これはSpecification（動的クエリビルダAPI）を使わずに済む簡易な方法だが、
     * 条件が増えると可読性が落ちるため、将来的にはSpecificationへの移行も検討する。
     *
     * @param keyword    銘柄名の部分一致キーワード（未指定時はnull）
     * @param sakeTypeName 酒種名（未指定時はnull）
     * @param region     産地（完全一致、未指定時はnull）
     * @param pageable   ページ番号・件数・ソート条件
     */
    @Query("""
            SELECT s FROM Sake s LEFT JOIN s.brewery b
            WHERE (:keyword IS NULL OR
                   s.name LIKE CONCAT('%', :keyword, '%') OR
                   b.name LIKE CONCAT('%', :keyword, '%') OR
                   b.prefecture LIKE CONCAT('%', :keyword, '%') OR
                   s.sakeType.name LIKE CONCAT('%', :keyword, '%') OR
                   s.region LIKE CONCAT('%', :keyword, '%') OR
                   s.description LIKE CONCAT('%', :keyword, '%'))
              AND (:sakeTypeName IS NULL OR s.sakeType.name = :sakeTypeName)
              AND (:region IS NULL OR s.region = :region)
            """)
    Page<Sake> search(
            @Param("keyword") String keyword,
            @Param("sakeTypeName") String sakeTypeName,
            @Param("region") String region,
            Pageable pageable);

    @Query("SELECT DISTINCT s.region FROM Sake s WHERE s.region IS NOT NULL AND s.region <> '' ORDER BY s.region")
    java.util.List<String> findDistinctRegions();

    @Query("SELECT DISTINCT s.sakeType.name FROM Sake s ORDER BY s.sakeType.name")
    java.util.List<String> findDistinctTypeNames();
}
