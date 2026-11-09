package com.example.demo.controller;

import com.example.demo.service.SakeCatalogService;
import com.example.demo.service.SakeCollectionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 既存の特集URLを維持し、ページごとに表示する案内やカタログ情報を渡す。 */
@Controller
public class SakeCollectionController {
    private final SakeCatalogService catalog;
    private final SakeCollectionService collections;

    public SakeCollectionController(SakeCatalogService catalog, SakeCollectionService collections) {
        this.catalog = catalog;
        this.collections = collections;
    }

    @GetMapping("/ranking")
    public String ranking(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("ranking", catalog.ranking(page));
        return collectionPage(model, "ranking", "公開レビューランキング",
                "公開レビューの平均評価が高い銘柄を紹介します。同じ評価の場合は、レビュー件数が多い銘柄を先に表示します。");
    }

    @GetMapping("/pairings")
    public String pairings(Model model) {
        model.addAttribute("foodSuggestions", collections.foodSuggestions());
        return collectionPage(model, "pairings", "おつまみ・酒器",
                "日本酒の香りや味わいに合わせて、食卓の一品と酒器を選んでみましょう。");
    }

    @GetMapping("/goods")
    public String goods(Model model) {
        return collectionPage(model, "goods", "日本酒の関連商品",
                "晩酌や贈り物に使える酒器の例を、メーカーの公式ページから探せます。");
    }

    @GetMapping("/brewery-map")
    public String breweryMap(Model model) {
        model.addAttribute("breweries", collections.breweriesByPrefecture());
        return collectionPage(model, "brewery-map", "酒蔵のマップ",
                "登録されている酒蔵を都道府県ごとに探せます。地図リンクを開くと、Google マップで酒蔵名を検索します。");
    }

    @GetMapping("/categories")
    public String categories(Model model) {
        model.addAttribute("types", catalog.types());
        return collectionPage(model, "categories", "酒種から探す",
                "純米酒や吟醸酒など、カタログに登録されている種類から銘柄を探せます。");
    }

    @GetMapping("/tags")
    public String tags(Model model) {
        model.addAttribute("tastes", catalog.tastes());
        return collectionPage(model, "tags", "味わい・香りのタグ",
                "気になる特徴を選ぶと、そのタグが付いた銘柄を検索できます。");
    }

    private String collectionPage(Model model, String currentPage, String title, String description) {
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("title", title);
        model.addAttribute("description", description);
        return "collection";
    }
}
