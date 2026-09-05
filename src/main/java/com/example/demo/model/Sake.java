package com.example.demo.model;

import java.util.Map;

/**
 * JPAエンティティを画面表示と推薦計算に適した形へ変換した読み取り専用DTO。
 *
 * @param id          地酒ID
 * @param name        銘柄名
 * @param breweryName 蔵元名（未登録の場合は空文字）
 * @param breweryPrefecture 蔵元の都道府県（未登録の場合は空文字）
 * @param type        酒種（純米大吟醸・吟醸 等）
 * @param region      産地
 * @param abv         アルコール度数(%)
 * @param price       価格(円)
 * @param description 説明文
 * @param imageUrl    商品画像URL
 * @param tagScores   タグ名→強さスコアのマップ（コサイン類似度計算での特徴ベクトルとして使用）
 */
public record Sake(
        long id,
        String name,
        String breweryName,
        String breweryPrefecture,
        String type,
        String region,
        double abv,
        int price,
        String description,
        String imageUrl,
        Map<String, Integer> tagScores
) {
}
