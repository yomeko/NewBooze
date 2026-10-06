package com.example.demo.controller;

import com.example.demo.service.SakeCatalogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * ホーム画面と、ランキングなどの案内画面を担当する。
 * ModelはHTMLへ渡すデータの入れ物。例えばfeaturedという名前で注目銘柄を渡すと、
 * home.htmlの${featured}からその銘柄一覧を使える。
 */
@Controller
public class HomeController {

    private final SakeCatalogService catalogService;

    // コンストラクタインジェクション。@Autowiredを付けなくても、
    // コンストラクタが1つだけの場合はSpringが自動でDIしてくれる。
    public HomeController(SakeCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * ランダムに選んだ最大4件の注目銘柄を、home.htmlへ渡す。
     */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("featured", catalogService.featured());
        return "home"; // templates/home.html を返す
    }

    /**
     * 人気ランキングの案内画面を表示する。掲載内容は現在準備中。
     */
    @GetMapping("/ranking")
    public String ranking(Model model) {
        return collectionPage(model, "ranking", "人気ランキング",
                "みんなに選ばれている日本酒を紹介します。");
    }

    /**
     * おつまみ・酒器の案内画面を表示する。掲載内容は現在準備中。
     */
    @GetMapping("/pairings")
    public String pairings(Model model) {
        return collectionPage(model, "pairings", "おすすめのおつまみ・グラス系",
                "日本酒をもっと楽しむためのおつまみや酒器を紹介します。");
    }

    /**
     * 関連商品の案内画面を表示する。掲載内容は現在準備中。
     */
    @GetMapping("/goods")
    public String goods(Model model) {
        return collectionPage(model, "goods", "日本酒関連の商品",
                "日本酒のある時間を豊かにする関連商品を紹介します。");
    }

    /**
     * 酒蔵マップの準備中画面を表示する。
     */
    @GetMapping("/brewery-map")
    public String breweryMap(Model model) {
        return collectionPage(model, "brewery-map", "酒蔵のマップ", "酒蔵のマップの掲載内容を準備しています。");
    }

    /**
     * お問い合わせの準備中画面を表示する。
     */
    @GetMapping("/contact")
    public String contact(Model model) {
        return collectionPage(model, "contact", "お問い合わせ", "お問い合わせの掲載内容を準備しています。");
    }

    /**
     * カテゴリの準備中画面を表示する。
     */
    @GetMapping("/categories")
    public String categories(Model model) {
        return collectionPage(model, "categories", "カテゴリ", "カテゴリの掲載内容を準備しています。");
    }

    /**
     * おすすめタグの準備中画面を表示する。
     */
    @GetMapping("/tags")
    public String tags(Model model) {
        return collectionPage(model, "tags", "おすすめのタグ", "おすすめのタグの掲載内容を準備しています。");
    }

    /**
     * 運営者情報の準備中画面を表示する。
     */
    @GetMapping("/about")
    public String about(Model model) {
        return collectionPage(model, "about", "運営者情報", "運営者情報の掲載内容を準備しています。");
    }

    /**
     * プライバシーポリシーの準備中画面を表示する。
     */
    @GetMapping("/privacy")
    public String privacy(Model model) {
        return collectionPage(model, "privacy", "プライバシーポリシー", "プライバシーポリシーの掲載内容を準備しています。");
    }

    /**
     * 利用者情報の送信についての準備中画面を表示する。
     */
    @GetMapping("/external-transmission")
    public String externalTransmission(Model model) {
        return collectionPage(model, "external-transmission", "利用者情報の送信について", "利用者情報の送信についての掲載内容を準備しています。");
    }

    /**
     * 同じ案内用HTMLを使い、見出し・説明文・現在のメニュー項目だけを切り替える。
     */
    private String collectionPage(Model model, String currentPage, String title, String description) {
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("title", title);
        model.addAttribute("description", description);
        return "collection";
    }
}
