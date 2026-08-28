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
 * 現時点ではSakeCatalogService（インメモリのダミーデータ）を参照しており、
 * 濵田担当のデータ収集完了後、entity.Sake + SakeRepositoryベースへ
 * 差し替える予定（内部設計書 第8章「今後の課題」参照）。
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
     * @param keyword キーワード（銘柄名の部分一致）。未入力時は空文字がデフォルトで入る
     * @param type    酒種による絞り込み（未指定可）
     * @param region  産地による絞り込み（未指定可）
     * @param page    ページ番号（0始まり）。未指定時は1ページ目(0)
     */
    @GetMapping("/search")
    public String search(@RequestParam(defaultValue = "") String keyword,
                          @RequestParam(required = false) String type,
                          @RequestParam(required = false) String region,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        // 検索結果本体（ページネーション情報込みのSakePageDto）
        model.addAttribute("result", catalogService.search(keyword, type, region, page));
        // 絞り込み用プルダウンの選択肢一覧
        model.addAttribute("types", catalogService.types());
        model.addAttribute("regions", catalogService.regions());
        // 画面再表示時に検索条件を保持するため、入力値をそのまま画面へ戻す
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedRegion", region);
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
