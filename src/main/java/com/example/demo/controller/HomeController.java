package com.example.demo.controller;

import com.example.demo.service.SakeDiscoveryService;
import com.example.demo.config.PublicationInformation;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * ホーム、初心者ガイド、運営案内を担当する。
 * ModelはHTMLへ渡すデータの入れ物。discoveryに抽選銘柄と比較銘柄を入れると、
 * home.htmlの${discovery}からその情報を使える。
 */
@Controller
public class HomeController {

    private final SakeDiscoveryService discoveryService;
    private final PublicationInformation publication;

    // コンストラクタインジェクション。@Autowiredを付けなくても、
    // コンストラクタが1つだけの場合はSpringが自動でDIしてくれる。
    public HomeController(SakeDiscoveryService discoveryService, PublicationInformation publication) {
        this.discoveryService = discoveryService;
        this.publication = publication;
    }

    /**
     * ランダムな1銘柄と、それに似たメジャー銘柄をhome.htmlへ渡す。
     */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("discovery", discoveryService.discover().orElse(null));
        model.addAttribute("majorReferences", discoveryService.majorReferences());
        model.addAttribute("nextDraw", java.util.UUID.randomUUID().toString());
        return "home"; // templates/home.html を返す
    }

    @GetMapping("/guide")
    public String guide() {
        return "guide";
    }

    /**
     * 設定された問い合わせ先と、窓口が未設定の場合の案内を表示する。
     */
    @GetMapping("/contact")
    public String contact(Model model) {
        model.addAttribute("publication", publication);
        return "contact";
    }

    /**
     * 運営者情報と研究終了に伴う運用方針を表示する。
     */
    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("publication", publication);
        return "about";
    }

    /** 登録前から、現在の機能で扱う情報と利用目的を確認できるようにする。 */
    @GetMapping("/privacy")
    public String privacy(Model model) {
        model.addAttribute("publication", publication);
        return "privacy";
    }

    /**
     * 現在の外部接続先と送信される情報を表示する。
     */
    @GetMapping("/external-transmission")
    public String externalTransmission(Model model) {
        return "external-transmission";
    }

}
