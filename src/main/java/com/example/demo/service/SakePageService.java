package com.example.demo.service;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 日本酒の紹介文、飲み方、料理、購入リンクを取得する。
 * 管理画面で保存したDBの情報を優先し、既存銘柄はJSONの紹介情報を使う。
 * 登録した詳細情報は基本情報と同じトランザクションでDBへ保存する。
 */
@Service
public class SakePageService {
    private final Map<Long, SakePage> pages;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public SakePageService(ObjectMapper mapper, Resource source) throws IOException {
        this(mapper, source, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SakePageService(ObjectMapper mapper,
            @Value("${app.sake-pages:classpath:data/sake-pages.json}") Resource source, org.springframework.jdbc.core.JdbcTemplate jdbc) throws IOException {
        this.mapper = mapper;
        this.jdbc = jdbc;
        Map<Long, SakePage> loaded = new LinkedHashMap<>();
        // JSONを読み終えたら入力を自動で閉じる。同じ銘柄IDが2件あれば起動時にエラーを出す。
        try (var input = source.getInputStream()) {
            for (SakePage page : mapper.readValue(input, SakePage[].class)) {
                if (loaded.putIfAbsent(page.id(), page) != null)
                    throw new IllegalArgumentException("日本酒IDが重複しています: " + page.id());
            }
        }
        pages = Map.copyOf(loaded);
    }

    /**
     * 銘柄IDに対応する紹介情報を返す。紹介情報や表示名が未登録ならDBの情報で補う。
     */
    public SakePage pageFor(Sake sake) {
        // JSONに紹介情報がまだない新規銘柄は、DBのID・名前・酒蔵だけで詳細画面を表示する。
        if (jdbc != null) {
            var saved = jdbc.query("SELECT detail_json FROM sake_page_details WHERE sake_id = ?",
                    (row, index) -> mapper.readValue(row.getString(1), SakePage.class), sake.id());
            if (!saved.isEmpty()) return saved.getFirst();
        }
        SakePage page = pages.getOrDefault(sake.id(), new SakePage(sake.id(), sake.name(), "",
                sake.breweryName(), null, null, null, null, null, null, null, null, null));
        if (!page.name().isEmpty()) return page;
        return new SakePage(page.id(), sake.name(), page.introduction(), page.brewery(),
                page.taste(), page.aroma(), page.prices(), page.drinking(), page.food(),
                page.recommendedFor(), page.officialUrl(), page.similarIds(), page.purchaseLinks(), page.abvText());
    }

    /** 検索結果の度数と容量付き価格。1ページ分の詳細JSONを一度だけ取得する。 */
    public record SpecificationLabels(String abv, String price) { }

    private record SavedSpecifications(String abvText, List<SakePage.Price> prices) {
        private static final SavedSpecifications EMPTY = new SavedSpecifications("", List.of());
    }

    public Map<Long, SpecificationLabels> specificationsFor(List<Sake> sakeList) {
        return specificationsFor(sakeList, true);
    }

    /** 度数だけを必要とする既存の呼出元とも互換性を保つ。 */
    public Map<Long, String> abvLabelsFor(List<Sake> sakeList) {
        Map<Long, String> labels = new LinkedHashMap<>();
        specificationsFor(sakeList, false).forEach((id, value) -> labels.put(id, value.abv()));
        return Map.copyOf(labels);
    }

    private Map<Long, SpecificationLabels> specificationsFor(List<Sake> sakeList, boolean includePrices) {
        if (sakeList.isEmpty()) return Map.of();
        Map<Long, SavedSpecifications> saved = new LinkedHashMap<>();
        List<Long> ids = sakeList.stream()
                .filter(sake -> includePrices || !Double.isFinite(sake.abv()) || sake.abv() <= 0)
                .map(Sake::id).distinct().toList();
        if (jdbc != null && !ids.isEmpty()) {
            Map<Long, String> names = new LinkedHashMap<>();
            sakeList.forEach(sake -> names.put(sake.id(), sake.name()));
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            jdbc.query("SELECT sake_id, detail_json FROM sake_page_details WHERE sake_id IN (" + placeholders + ")",
                    (row, index) -> {
                        long id = row.getLong("sake_id");
                        return Map.entry(id, specificationsFromJson(row.getString("detail_json"), id, names.get(id)));
                    },
                    ids.toArray()).forEach(entry -> saved.put(entry.getKey(), entry.getValue()));
        }
        Map<Long, SpecificationLabels> labels = new LinkedHashMap<>();
        for (Sake sake : sakeList) {
            SakePage fallback = pages.get(sake.id());
            var details = saved.getOrDefault(sake.id(), fallback == null ? SavedSpecifications.EMPTY
                    : new SavedSpecifications(fallback.abvText(), fallback.prices()));
            labels.put(sake.id(), new SpecificationLabels(SakePage.formatAbv(sake.abv(), details.abvText()),
                    SakePage.formatPrice(sake.price(), details.prices())));
        }
        return Map.copyOf(labels);
    }

    /** 紹介文や外部URLの不備で検索全体を止めず、度数と容量別価格だけを読む。 */
    private SavedSpecifications specificationsFromJson(String json, long sakeId, String sakeName) {
        if (json == null || json.isBlank()) return SavedSpecifications.EMPTY;
        try {
            var details = mapper.readTree(json);
            if (details == null || !details.isObject()) return SavedSpecifications.EMPTY;
            var id = details.get("id");
            if (id != null && !id.isNull()
                    && (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != sakeId))
                return SavedSpecifications.EMPTY;
            var name = details.get("name");
            if (name != null && !name.isNull()
                    && (!name.isString() || (!SakePage.clean(name.stringValue()).isEmpty()
                        && !SakePage.clean(name.stringValue()).equals(SakePage.clean(sakeName)))))
                return SavedSpecifications.EMPTY;
            String text = "";
            var value = details.get("abvText");
            if (value != null && value.isString()) {
                String candidate = SakePage.clean(value.stringValue());
                if (candidate.codePointCount(0, candidate.length()) <= 120) text = candidate;
            }
            var prices = new java.util.ArrayList<SakePage.Price>();
            var values = details.get("prices");
            if (values != null && values.isArray()) {
                for (var price : values) {
                    if (!price.isObject()) continue;
                    var amount = price.get("yen");
                    var volume = price.get("volume");
                    if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToInt()
                            || amount.intValue() <= 0) continue;
                    if (volume != null && !volume.isNull() && !volume.isString()) continue;
                    String capacity = volume != null && volume.isString() ? SakePage.clean(volume.stringValue()) : "";
                    if (capacity.codePointCount(0, capacity.length()) > 120) continue;
                    prices.add(new SakePage.Price(capacity, amount.intValue()));
                }
            }
            return new SavedSpecifications(text, List.copyOf(prices));
        } catch (JacksonException ignored) {
            return SavedSpecifications.EMPTY;
        }
    }

    /** 登録フォームの紹介情報を、基本情報と同じDBに保存する。 */
    public void save(SakePage page) {
        jdbc.update("INSERT INTO sake_page_details (sake_id, detail_json) VALUES (?, ?)",
                page.id(), mapper.writeValueAsString(page));
    }
}
