package com.example.demo.controller;

import com.example.demo.service.AnalyticsRequestPolicy;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.SakeCatalogService;
import com.example.demo.service.SakePageService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/** 登録済みの公式URLだけに遷移し、遷移1回と銘柄の各タグをまとめて加算する。 */
@Controller
public class SakeOutboundController {
    private static final Logger log = LoggerFactory.getLogger(SakeOutboundController.class);
    private final SakeCatalogService catalog;
    private final SakePageService pages;
    private final AnalyticsService analytics;

    public SakeOutboundController(SakeCatalogService catalog, SakePageService pages, AnalyticsService analytics) {
        this.catalog = catalog;
        this.pages = pages;
        this.analytics = analytics;
    }

    @GetMapping("/sake/{id}/outbound/{destination:official|purchase}")
    public ResponseEntity<Void> open(@PathVariable long id, @PathVariable String destination,
            HttpServletRequest request) {
        var sake = catalog.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var page = pages.pageFor(sake);
        String url = "purchase".equals(destination)
                ? page.purchaseLinks().stream().filter(link -> link.official()).findFirst()
                        .map(link -> link.url()).orElse(page.officialUrl())
                : page.officialUrl();
        if (url.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (AnalyticsRequestPolicy.isHumanNavigation(request)) {
            try {
                analytics.recordOfficialClick(id);
            } catch (DataAccessException | TransactionException ex) {
                // 集計障害で利用者の公式サイトへの移動を妨げない。失敗をログで識別する。
                log.warn("公式サイトへの遷移を集計できませんでした (sakeId={}, {})",
                        id, ex.getClass().getSimpleName());
            }
        }
        // キャッシュされた302によって次のクリックがサーバーを通らなくなるのを防ぐ。
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url))
                .cacheControl(CacheControl.noStore()).build();
    }
}
