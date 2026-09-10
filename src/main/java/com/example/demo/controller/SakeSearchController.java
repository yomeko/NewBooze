package com.example.demo.controller;

import com.example.demo.service.SakeCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * S04（検索結果一覧画面）／S05（地酒詳細画面）を担当するController。
 * SakeCatalogService を通じて JPA カタログを参照する。
 */
@Controller
public class SakeSearchController {

    private final SakeCatalogService catalogService;

    public SakeSearchController(SakeCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * S04: 検索結果一覧画面表示。
     * 外部設計書 4.4の入出力仕様に対応。
     *
     * @param keyword 味の特徴・風味のキーワード。未入力時は空文字がデフォルトで入る
     * @param page    ページ番号（0始まり）。未指定時は1ページ目(0)
     */
    @GetMapping("/search")
    public String search(@RequestParam(defaultValue = "") String keyword,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        // 検索結果本体（ページネーション情報込みのSakePageDto）
        model.addAttribute("result", catalogService.search(keyword, null, null, page));
        // 画面再表示時に検索条件を保持するため、入力値をそのまま画面へ戻す
        model.addAttribute("keyword", keyword);
        return "search"; // templates/search.html を返す
    }

    /**
     * S05: 地酒詳細画面表示。
     * 該当IDの銘柄が存在しない場合は404(Not Found)を返す。
     */
    @GetMapping("/sake/{id}")
    public String detail(@PathVariable long id, Model model) {
        model.addAttribute("sake", catalogService.findById(id)
                // ResponseStatusExceptionを投げると、Spring MVCが自動でHTTPステータス404の
                // エラーレスポンスに変換してくれる（専用のExceptionHandlerを書く必要がない）
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "detail"; // templates/detail.html を返す
    }
}
