package com.example.demo.dto;

import com.example.demo.model.Sake;
import java.util.List;

/**
 * S04（検索結果一覧画面）向けのページネーション結果DTO。
 *
 * 要件定義書 5.3／7.2の設計方針の通り、Spring Data JPAの標準インタフェースである
 * {@code Page<T>}（content, totalElements, totalPages, pageNumber等のプロパティを持つ）
 * を模した独自クラスとして設計している。
 * 現状はDB未接続でmodel.Sake（インメモリ）を扱っているためこの独自DTOを使っているが、
 * entity.Sake + SakeRepositoryへ移行した際は、Spring Data標準のPage<Sake>を
 * 直接使う形に置き換えられるよう、あえて同じプロパティ構成に揃えてある
 * （＝Thymeleaf側の記述（result.content等）を極力変更せずに済ませる狙い）。
 *
 * @param content       このページに含まれる地酒一覧
 * @param pageNumber    現在のページ番号（0始まり）
 * @param totalPages    総ページ数
 * @param totalElements 絞り込み条件に合致した総件数
 */
public record SakePageDto(List<Sake> content, int pageNumber, int totalPages, long totalElements) {

    /** 前のページが存在するか（画面の「前へ」リンクの表示制御に使用） */
    public boolean hasPrevious() { return pageNumber > 0; }

    /** 次のページが存在するか（画面の「次へ」リンクの表示制御に使用） */
    public boolean hasNext() { return pageNumber + 1 < totalPages; }
}
