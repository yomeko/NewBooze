package com.example.demo.repository;

import com.example.demo.entity.Sake;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 日本酒を保存し、検索条件に合う銘柄やIDに対応する銘柄を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface SakeRepository extends JpaRepository<Sake, Long> {

    // ホーム画面で抽選するため、銘柄の詳細ではなくIDだけを取得する。
    @Query("SELECT s.id FROM Sake s")
    java.util.List<Long> findAllIds();

    /**
     * S04（検索結果一覧画面）向けの多条件検索。
     * キーワード／酒種／価格帯／味わいタグで絞り込み、
     * ページネーション情報付きで返す。
     *
     * JPQLでは「未入力の条件はスキップする」動的検索を、
     * "(:param IS NULL OR 条件)" という書き方で表現できる。
     * これはSpecification（動的クエリビルダAPI）を使わずに済む簡易な方法だが、
     * 条件が増えると可読性が落ちるため、将来的にはSpecificationへの移行も検討する。
     *
     * タグ名は完全一致で検索し、詳細ページに表示する正のスコアのタグだけを対象にする。
     * EXISTSを使うことで、複数のタグを持つ商品も検索結果で重複しない。
     *
     * @param name       銘柄名の部分一致キーワード（未指定時はnull）
     * @param keyword    全体検索キーワード（未指定時はnull）
     * @param sakeTypeName 酒種名（未指定時はnull）
     * @param minPrice   最低価格（未指定時はnull）
     * @param maxPrice   最高価格（未指定時はnull）
     * @param taste      味わいタグ名（未指定時はnull）
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
                   s.description LIKE CONCAT('%', :keyword, '%') OR
                   EXISTS (SELECT st.id FROM SakeTag st
                           WHERE st.sake = s AND st.score > 0
                             AND st.tag.name = :keyword))
              AND (:name IS NULL OR s.name LIKE CONCAT('%', :name, '%'))
              AND (:sakeTypeName IS NULL OR s.sakeType.name = :sakeTypeName)
              AND (:minPrice IS NULL OR s.price >= :minPrice)
              AND (:maxPrice IS NULL OR s.price <= :maxPrice)
              AND (:taste IS NULL OR EXISTS (
                    SELECT st.id FROM SakeTag st
                    WHERE st.sake = s AND st.score > 0 AND st.tag.name = :taste
              ))
            """)
    Page<Sake> search(
            @Param("keyword") String keyword,
            @Param("name") String name,
            @Param("sakeTypeName") String sakeTypeName,
            @Param("minPrice") Integer minPrice,
            @Param("maxPrice") Integer maxPrice,
            @Param("taste") String taste,
            Pageable pageable);

    // DISTINCTで同じ産地を1回だけ返し、未登録や空欄の産地は除く。
    @Query("SELECT DISTINCT s.region FROM Sake s WHERE s.region IS NOT NULL AND s.region <> '' ORDER BY s.region")
    java.util.List<String> findDistinctRegions();

    // 登録されている銘柄から酒種名を重複なしで取り出し、検索フォームの選択肢にする。
    @Query("SELECT DISTINCT s.sakeType.name FROM Sake s ORDER BY s.sakeType.name")
    java.util.List<String> findDistinctTypeNames();
}
