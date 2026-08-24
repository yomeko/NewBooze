package com.example.demo.model;

import java.util.Map;

/** DB移行前の画面表示・推薦計算用モデル。 */
public record Sake(
        long id,
        String name,
        String type,
        String region,
        double abv,
        int price,
        String description,
        Map<String, Integer> tagScores
) {
}
