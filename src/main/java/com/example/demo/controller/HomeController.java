package com.example.demo.controller;

import com.example.demo.service.SakeCatalogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * S01（トップ画面）を担当するController。
 * 外部設計書 3.3 S01の通り、注目銘柄をマーキー表示し、
 * 「診断する」「検索する」への導線を提供する画面のデータを準備する。
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
     * トップページ表示。
     * 注目銘柄（先頭5件、SakeCatalogService.featured()参照）を
     * マーキー表示用データとしてThymeleafテンプレート(home.html)へ渡す。
     */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("featured", catalogService.featured());
        return "home"; // templates/home.html を返す
    }
}
