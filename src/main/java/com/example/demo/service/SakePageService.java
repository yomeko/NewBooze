package com.example.demo.service;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
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
                page.recommendedFor(), page.officialUrl(), page.similarIds(), page.purchaseLinks());
    }
    /** 登録フォームの紹介情報を、基本情報と同じDBに保存する。 */
    public void save(SakePage page) {
        jdbc.update("INSERT INTO sake_page_details (sake_id, detail_json) VALUES (?, ?)",
                page.id(), mapper.writeValueAsString(page));
    }
}
