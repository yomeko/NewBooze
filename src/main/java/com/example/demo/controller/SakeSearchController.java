package com.example.demo.controller;

import com.example.demo.service.SakeCatalogService;
import com.example.demo.service.SakePageService;
import com.example.demo.service.SakeInteractionService;
import com.example.demo.security.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final SakePageService pages;
    private final SakeInteractionService interactions;

    public SakeSearchController(SakeCatalogService catalogService, SakePageService pages,
            SakeInteractionService interactions) {
        this.catalogService = catalogService;
        this.pages = pages;
        this.interactions = interactions;
    }

    /**
     * S04: 検索結果一覧画面表示。
     * 外部設計書 4.4の入出力仕様に対応。
     *
     * @param name    銘柄名の部分一致キーワード
     * @param keyword 味の特徴・風味のキーワード。未入力時は空文字がデフォルトで入る
     * @param type    酒種による絞り込み
     * @param taste   辛口・甘口による絞り込み
     * @param priceRange 公式販売価格の価格帯
     * @param sort    並び順
     * @param page    ページ番号（0始まり）。未指定時は1ページ目(0)
     */
    @GetMapping("/search")
    public String search(@RequestParam(defaultValue = "") String keyword,
                          @RequestParam(defaultValue = "") String name,
                          @RequestParam(defaultValue = "") String type,
                          @RequestParam(defaultValue = "") String taste,
                          @RequestParam(defaultValue = "") String priceRange,
                          @RequestParam(defaultValue = "recommended") String sort,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        Integer minPrice = switch (priceRange) {
            case "1500-2999" -> 1500;
            case "3000-4999" -> 3000;
            case "5000-up" -> 5000;
            default -> null;
        };
        Integer maxPrice = switch (priceRange) {
            case "under-1500" -> 1499;
            case "1500-2999" -> 2999;
            case "3000-4999" -> 4999;
            default -> null;
        };
        // 検索結果本体（ページネーション情報込みのSakePageDto）
        model.addAttribute("result", catalogService.search(
                keyword, name, type, minPrice, maxPrice, taste, sort, page));
        model.addAttribute("types", catalogService.types());
        // 画面再表示時に検索条件を保持するため、入力値をそのまま画面へ戻す
        model.addAttribute("keyword", keyword);
        model.addAttribute("name", name);
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedTaste", taste);
        model.addAttribute("selectedPriceRange", priceRange);
        model.addAttribute("selectedSort", sort);
        return "search"; // templates/search.html を返す
    }

    /**
     * S05: 地酒詳細画面表示。
     * 該当IDの銘柄が存在しない場合は404(Not Found)を返す。
     */
    @GetMapping("/sake/{id}")
    public String detail(@PathVariable long id, @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "newest") String reviewSort,
            @RequestParam(defaultValue = "0") int reviewPage, Model model) {
        var sake = catalogService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var page = pages.pageFor(sake);
        model.addAttribute("sake", sake);
        model.addAttribute("sakeTags", sake.tagScores().entrySet().stream()
                .filter(tag -> tag.getValue() > 0)
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(java.util.Map.Entry.comparingByKey()))
                .map(java.util.Map.Entry::getKey).toList());
        model.addAttribute("page", page);
        model.addAttribute("similar", page.similarIds().stream()
                .map(catalogService::findById).flatMap(java.util.Optional::stream)
                .map(pages::pageFor).toList());
        model.addAttribute("favorite", principal != null && interactions.isFavorite(principal.getUserId(), id));
        var saved = principal == null ? java.util.Optional.<SakeInteractionService.Review>empty()
                : interactions.review(principal.getUserId(), id);
        model.addAttribute("hasReview", saved.isPresent());
        model.addAttribute("publicReviews", interactions.publicReviews(id, reviewSort, reviewPage));
        if (!model.containsAttribute("review"))
            model.addAttribute("review", saved.orElse(new SakeInteractionService.Review(0, "", true)));
        return "detail"; // templates/detail.html を返す
    }
}
