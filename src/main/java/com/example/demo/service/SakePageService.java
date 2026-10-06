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
 * 日本酒の紹介文、飲み方、料理、購入リンクをJSONファイルから読み込む。
 * 起動時に日本酒IDごとの一覧を作り、詳細画面を表示するときに対応する情報を返す。
 * 基本情報はDBにあり、ここでは追加の紹介情報を扱う。
 */
@Service
public class SakePageService {
    private final Map<Long, SakePage> pages;

    public SakePageService(ObjectMapper mapper,
            @Value("${app.sake-pages:classpath:data/sake-pages.json}") Resource source) throws IOException {
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
        SakePage page = pages.getOrDefault(sake.id(), new SakePage(sake.id(), sake.name(), "",
                sake.breweryName(), null, null, null, null, null, null, null, null, null));
        if (!page.name().isEmpty()) return page;
        return new SakePage(page.id(), sake.name(), page.introduction(), page.brewery(),
                page.taste(), page.aroma(), page.prices(), page.drinking(), page.food(),
                page.recommendedFor(), page.officialUrl(), page.similarIds(), page.purchaseLinks());
    }
}
