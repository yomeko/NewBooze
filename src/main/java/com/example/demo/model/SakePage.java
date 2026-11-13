package com.example.demo.model;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * JSONから読む日本酒の追加紹介情報。DBの基本情報とは別に、飲み方や料理をまとめる。
 * 生成時に空欄や重複を整理し、不正な価格や外部URLを検出する。
 * 未登録の一覧は空の一覧にそろえ、HTML側でその項目を省略できるようにする。
 */
public record SakePage(long id, String name, String introduction, String brewery,
        List<String> taste, List<String> aroma, List<Price> prices,
        List<String> drinking, List<String> food, List<String> recommendedFor,
        String officialUrl, List<Long> similarIds, List<PurchaseLink> purchaseLinks, String abvText) {
    /** abvTextのない既存の登録処理・紹介JSONと互換性を保つ。 */
    public SakePage(long id, String name, String introduction, String brewery,
            List<String> taste, List<String> aroma, List<Price> prices,
            List<String> drinking, List<String> food, List<String> recommendedFor,
            String officialUrl, List<Long> similarIds, List<PurchaseLink> purchaseLinks) {
        this(id, name, introduction, brewery, taste, aroma, prices, drinking, food,
                recommendedFor, officialUrl, similarIds, purchaseLinks, "");
    }

    // recordを作ると必ず通る確認処理。JSONの読み込み時もここで値を整理する。
    public SakePage {
        if (id <= 0) throw new IllegalArgumentException("日本酒IDは正の整数にしてください");
        name = clean(name);
        introduction = clean(introduction);
        brewery = clean(brewery);
        abvText = clean(abvText);
        if (abvText.codePointCount(0, abvText.length()) > 120)
            throw new IllegalArgumentException("度数の原文は120文字以内にしてください: " + id);
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
        // 似た銘柄のIDから空欄・自分自身・重複を除き、最大20件にする。
        similarIds = similarIds == null ? List.of() : similarIds.stream()
                .filter(v -> v != null && v > 0 && v != id).distinct().limit(20).toList();
        // 有効な購入URLだけを残し、公式ショップを先に並べる。
        purchaseLinks = purchaseLinks == null ? List.of() : purchaseLinks.stream()
                .filter(v -> v != null && !v.url().isEmpty())
                .sorted(Comparator.comparing(PurchaseLink::official).reversed()).toList();
    }

    /** 単一の登録値を優先し、値がなければ公式の範囲表記をそのまま表示する。 */
    public String abvDisplay(double numericAbv) {
        return formatAbv(numericAbv, abvText);
    }

    /** 一覧でも詳細と同じ度数表記を使い、範囲へ単位を重ねて付けない。 */
    public static String formatAbv(double numericAbv, String abvText) {
        if (Double.isFinite(numericAbv) && numericAbv > 0)
            return String.format(Locale.ROOT, "%.1f%%", numericAbv);
        abvText = clean(abvText);
        return abvText.isEmpty() ? "未確認" : abvText;
    }

    /** 確認できた720ml価格を先頭にし、同じ優先度の入力順・価格は変えない。 */
    public List<Price> orderedPrices() {
        return orderedPrices(prices);
    }

    private static List<Price> orderedPrices(List<Price> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(price -> {
                    if (price.yen() != null && price.is720ml()) return 0;
                    if (price.yen() != null && !price.volume().isEmpty()) return 1;
                    if (price.yen() != null) return 2;
                    return price.is720ml() ? 3 : 4;
                })).toList();
    }

    /** 数値の参考価格に別商品の容量を付けず、対応する確認済み価格を表示する。 */
    public static String formatPrice(int referencePrice, List<Price> prices) {
        var known = orderedPrices(prices).stream().filter(price -> price.yen() != null);
        if (referencePrice > 0) known = known.filter(price -> price.yen() == referencePrice);
        var selected = known.findFirst();
        if (selected.isPresent()) {
            Price price = selected.get();
            return (price.volume().isEmpty() ? "対象容量：未確認" : price.volume())
                    + " " + String.format(Locale.ROOT, "%,d円（税込）", price.yen());
        }
        return referencePrice > 0
                ? String.format(Locale.ROOT, "参考価格：%,d円（対象容量：未確認）", referencePrice)
                : "価格：未確認";
    }

    // 容量と価格の組み合わせ。価格が未登録ならnullを許し、登録する価格は正の整数に限る。
    public record Price(String volume, Integer yen) {
        public Price {
            volume = clean(volume);
            if (yen != null && yen <= 0) throw new IllegalArgumentException("価格は正の整数かnullにしてください");
        }

        private boolean is720ml() {
            return volume.replaceAll("[\\s　]+", "").equalsIgnoreCase("720ml");
        }
    }

    // 購入リンクの表示名・URL・公式かどうか。表示名が空なら公式ショップまたは販売店で補う。
    public record PurchaseLink(String label, String url, boolean official) {
        public PurchaseLink {
            label = clean(label);
            url = SakePage.url(url);
            if (label.isEmpty()) label = official ? "公式ショップ" : "販売店";
        }
    }

    /**
     * 未登録の文字列を空文字にそろえ、文字列の前後の空白を取り除く。
     */
    public static String clean(String value) { return value == null ? "" : value.strip(); }

    /**
     * 未登録の一覧を空の一覧にし、空文字と重複する項目を取り除く。
     */
    private static List<String> words(List<String> values) {
        return values == null ? List.of() : values.stream().map(SakePage::clean)
                .filter(v -> !v.isEmpty()).distinct().toList();
    }

    /**
     * 外部リンクがHTTPSのURLか、ホスト名があるか、認証情報が含まれていないかを確認する。
     * 空欄なら空欄のまま返し、条件に合わないURLは読み込みエラーにする。
     */
    private static String url(String value) {
        value = clean(value);
        if (value.isEmpty()) return value;
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("外部リンクはhttpsのURLにしてください: " + value);
        return value;
    }
}
