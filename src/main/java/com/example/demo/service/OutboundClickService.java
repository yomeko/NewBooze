package com.example.demo.service;

import com.example.demo.repository.OutboundClickRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 表示中の公式リンクだけを解決し、クリックの保存後に転送先を返す。 */
@Service
public class OutboundClickService {
    private static final int PAGE_SIZE = 30;
    private final OutboundClickRepository clicks;
    private final SakeCatalogService catalog;
    private final SakePageService pages;
    private final SakeDiscoveryService discovery;

    public OutboundClickService(OutboundClickRepository clicks, SakeCatalogService catalog,
            SakePageService pages, SakeDiscoveryService discovery) {
        this.clicks = clicks;
        this.catalog = catalog;
        this.pages = pages;
        this.discovery = discovery;
    }

    @Transactional
    public String follow(String source, String target, String kind, Long userId) {
        // URLや会員IDをフォームから受け取らず、転送先は登録済み情報から決める。
        Destination destination = switch (source == null ? "" : source) {
            case "detail" -> detail(target, kind);
            case "home" -> home(target, kind);
            case "guide" -> guide(target, kind);
            default -> throw missing();
        };
        if (destination.url() == null || destination.url().isBlank()) throw missing();
        clicks.insert(userId, destination.sakeId(), destination.name(), source, kind, destination.url());
        return destination.url();
    }

    private Destination detail(String target, String kind) {
        if (!"official".equals(kind) && !"purchase".equals(kind)) throw missing();
        long id;
        try {
            if (target == null || !target.matches("[0-9]+")) throw missing();
            id = Long.parseLong(target);
        } catch (NumberFormatException ex) {
            throw missing();
        }
        if (id <= 0) throw missing();
        var sake = catalog.findById(id).orElseThrow(OutboundClickService::missing);
        var page = pages.pageFor(sake);
        String url = "purchase".equals(kind)
                ? page.purchaseLinks().stream().filter(link -> link.official())
                        .map(link -> link.url()).findFirst().orElse(page.officialUrl())
                : page.officialUrl();
        return new Destination(id, sake.name(), url);
    }

    private Destination home(String target, String kind) {
        if (!"product".equals(kind) && !"retailer".equals(kind)) throw missing();
        var reference = discovery.majorReferences().stream()
                .filter(item -> item.name().equals(target)).findFirst()
                .orElseThrow(OutboundClickService::missing);
        return new Destination(null, reference.name(), "product".equals(kind)
                ? reference.productUrl() : reference.retailerUrl());
    }

    private Destination guide(String target, String kind) {
        Destination destination = switch (target == null ? "" : target) {
            case "dassai" -> new Destination(null, "獺祭", "https://dassai.com/dealer/");
            case "dewazakura" -> new Destination(null, "出羽桜", "https://www.dewazakura.co.jp/shop/");
            case "japansake" -> new Destination(null, "日本酒造組合中央会",
                    "https://japansake.or.jp/sake/en/basic/how-to-store-preserve-sake/");
            case "houraisen" -> new Destination(null, "蓬莱泉",
                    "https://netshop.houraisen.co.jp/products/detail/82");
            case "dassai-faq" -> new Destination(null, "獺祭 よくあるご質問", "https://dassai.com/faq/");
            default -> throw missing();
        };
        String expectedKind = "dassai".equals(target) || "dewazakura".equals(target)
                ? "retailer" : "reference";
        if (!expectedKind.equals(kind)) throw missing();
        return destination;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        var counts = clicks.summary();
        return new Summary(counts.total(), counts.anonymous(), counts.loggedIn());
    }

    @Transactional(readOnly = true)
    public AccountPage accounts(int requestedPage) {
        long total = clicks.accountCount();
        int number = (int) Math.min(Math.max(0, requestedPage), total == 0 ? 0 : (total - 1) / PAGE_SIZE);
        var content = clicks.accounts(PAGE_SIZE, (long) number * PAGE_SIZE).stream()
                .map(account -> new AccountCount(account.userId(), account.name(), account.clicks(),
                        account.lastClickedAt())).toList();
        return new AccountPage(content, number, number > 0,
                ((long) number + 1) * PAGE_SIZE < total, total);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "公式サイトのリンクが見つかりません。");
    }

    private record Destination(Long sakeId, String name, String url) {}
    public record Summary(long total, long anonymous, long loggedIn) {}
    public record AccountCount(long userId, String name, long clicks, LocalDateTime lastClickedAt) {}
    public record AccountPage(List<AccountCount> content, int number, boolean hasPrevious,
            boolean hasNext, long totalElements) {}
}
