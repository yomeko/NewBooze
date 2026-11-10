package com.example.demo.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 銘柄詳細との往復で使う検索条件だけを取り出し、固定の画面URLに付けられる形にする。 */
final class SakeSearchQuery {
    private static final List<String> FILTER_NAMES = List.of("keyword", "name", "type", "taste", "priceRange", "sort");

    private SakeSearchQuery() {}

    static Map<String, String> parameters(Map<String, String> request) {
        var filters = new LinkedHashMap<String, String>();
        for (String name : FILTER_NAMES) {
            String value = request.get(name);
            if (value != null && !value.isEmpty()) filters.put(name, value);
        }

        String page = request.get("page");
        if (page != null) {
            try {
                int number = Integer.parseInt(page);
                if (number >= 0) filters.put("page", Integer.toString(number));
            } catch (NumberFormatException ignored) {
                // 不正なページ番号は検索画面の初期値に任せる。
            }
        }
        return filters;
    }

    static String encoded(Map<String, String> filters) {
        return filters.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static String suffix(Map<String, String> request) {
        String query = encoded(parameters(request));
        return query.isEmpty() ? "" : "?" + query;
    }
}
