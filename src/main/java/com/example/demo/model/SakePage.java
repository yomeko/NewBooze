package com.example.demo.model;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

/** Photo-free editorial content. Empty values are intentionally hidden. */
public record SakePage(long id, String name, String introduction, String brewery,
        List<String> taste, List<String> aroma, List<Price> prices,
        List<String> drinking, List<String> food, List<String> recommendedFor,
        String officialUrl, List<Long> similarIds, List<PurchaseLink> purchaseLinks) {
    public SakePage {
        if (id <= 0) throw new IllegalArgumentException("日本酒IDは正の整数にしてください");
        name = clean(name);
        introduction = clean(introduction);
        brewery = clean(brewery);
        if (introduction.codePointCount(0, introduction.length()) > 160)
            throw new IllegalArgumentException("紹介文は約100文字（最大160文字）にしてください: " + id);
        taste = words(taste);
        aroma = words(aroma);
        drinking = words(drinking);
        food = words(food);
        recommendedFor = words(recommendedFor);
        prices = prices == null ? List.of() : prices.stream()
                .filter(p -> p != null && (p.yen() != null || !p.volume().isEmpty())).toList();
        officialUrl = url(officialUrl);
        similarIds = similarIds == null ? List.of() : similarIds.stream()
                .filter(v -> v != null && v > 0 && v != id).distinct().limit(20).toList();
        purchaseLinks = purchaseLinks == null ? List.of() : purchaseLinks.stream()
                .filter(v -> v != null && !v.url().isEmpty())
                .sorted(Comparator.comparing(PurchaseLink::official).reversed()).toList();
    }

    public record Price(String volume, Integer yen) {
        public Price {
            volume = clean(volume);
            if (yen != null && yen <= 0) throw new IllegalArgumentException("価格は正の整数かnullにしてください");
        }
    }

    public record PurchaseLink(String label, String url, boolean official) {
        public PurchaseLink {
            label = clean(label);
            url = SakePage.url(url);
            if (label.isEmpty()) label = official ? "公式ショップ" : "販売店";
        }
    }

    public static String clean(String value) { return value == null ? "" : value.strip(); }

    private static List<String> words(List<String> values) {
        return values == null ? List.of() : values.stream().map(SakePage::clean)
                .filter(v -> !v.isEmpty()).distinct().toList();
    }

    private static String url(String value) {
        value = clean(value);
        if (value.isEmpty()) return value;
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("外部リンクはhttpsのURLにしてください: " + value);
        return value;
    }
}
