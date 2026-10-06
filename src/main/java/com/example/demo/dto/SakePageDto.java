package com.example.demo.dto;

import com.example.demo.model.Sake;
import java.util.List;

/**
 * 検索結果の1ページ分の銘柄と、ページ移動に必要な情報をまとめたデータ。
 * ページ分割（ページネーション）により、一度に表示する銘柄の数を抑える。
 * pageNumberは0から数えるので、画面の1ページ目は0、2ページ目は1になる。
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
