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
 * 日本酒の検索画面と詳細画面を担当する。
 * 検索条件から銘柄を探し、HTMLが表示に使うデータをModelに入れる。
 * 詳細画面ではデータベースの基本情報に、JSONの紹介文や公開レビューを組み合わせる。
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
     * URLの検索条件を受け取り、条件に合う銘柄を1ページ分取得する。
     * {@code @RequestParam}はURLの?以降の値を受け取る指定。未指定の条件はdefaultValueを使う。
     * 価格帯の文字列を最低・最高価格へ変換し、入力済みの条件も画面へ返す。
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
     * URLのIDに対応する銘柄、紹介情報、味わいタグ、レビューを用意する。
     * 銘柄が存在しない場合は404を返す。お気に入りと自分のレビューはログイン中だけ調べる。
     * 入力エラー後のレビューがすでに渡されていれば、それを上書きせず再表示する。
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
