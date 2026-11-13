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

    /** 検索結果1ページ分の度数を一括取得し、詳細と同じ優先順で表示する。 */
    public Map<Long, String> abvLabelsFor(List<Sake> sakeList) {
        if (sakeList.isEmpty()) return Map.of();
        Map<Long, String> saved = new LinkedHashMap<>();
        List<Long> ids = sakeList.stream()
                .filter(sake -> !Double.isFinite(sake.abv()) || sake.abv() <= 0)
                .map(Sake::id).distinct().toList();
        if (jdbc != null && !ids.isEmpty()) {
            Map<Long, String> names = new LinkedHashMap<>();
            sakeList.forEach(sake -> names.put(sake.id(), sake.name()));
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            jdbc.query("SELECT sake_id, detail_json FROM sake_page_details WHERE sake_id IN (" + placeholders + ")",
                    (row, index) -> {
                        long id = row.getLong("sake_id");
                        return Map.entry(id, abvTextFromJson(row.getString("detail_json"), id, names.get(id)));
                    },
                    ids.toArray()).forEach(entry -> saved.put(entry.getKey(), entry.getValue()));
        }
        Map<Long, String> labels = new LinkedHashMap<>();
        for (Sake sake : sakeList) {
            SakePage fallback = pages.get(sake.id());
            String text = saved.getOrDefault(sake.id(), fallback == null ? "" : fallback.abvText());
            labels.put(sake.id(), SakePage.formatAbv(sake.abv(), text));
        }
        return Map.copyOf(labels);
    }

    /** 紹介文や外部URLの不備で検索全体を止めず、表示用の度数だけを読む。 */
    private String abvTextFromJson(String json, long sakeId, String sakeName) {
        if (json == null || json.isBlank()) return "";
        try {
            var details = mapper.readTree(json);
            if (details == null || !details.isObject()) return "";
            var id = details.get("id");
            if (id != null && !id.isNull()
                    && (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != sakeId)) return "";
            var name = details.get("name");
            if (name != null && !name.isNull()
                    && (!name.isString() || (!SakePage.clean(name.stringValue()).isEmpty()
                        && !SakePage.clean(name.stringValue()).equals(SakePage.clean(sakeName))))) return "";
            var value = details.get("abvText");
            if (value == null || !value.isString()) return "";
            String text = SakePage.clean(value.stringValue());
            return text.codePointCount(0, text.length()) <= 120 ? text : "";
        } catch (JacksonException ignored) {
            return "";
        }
    }

    /** 登録フォームの紹介情報を、基本情報と同じDBに保存する。 */
    public void save(SakePage page) {
        jdbc.update("INSERT INTO sake_page_details (sake_id, detail_json) VALUES (?, ?)",
                page.id(), mapper.writeValueAsString(page));
    }
}
