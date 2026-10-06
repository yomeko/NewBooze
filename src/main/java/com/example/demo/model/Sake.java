package com.example.demo.model;

import java.util.Map;

/**
 * 検索・詳細・診断で使う日本酒の基本情報と、特徴ごとの点数をまとめたデータ。
 * 同じ名前のentity.SakeはDB保存用で、こちらは読み取り用。
 * tagScoresは例えば「甘口 → 3」のように、特徴の名前とその強さを対応させる。
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
 * @param tagScores   特徴名と点数の対応表。好みとの類似度を計算するために使う
 * @param averageRating 公開レビューの平均の星の数。レビューがない場合はnull
 * @param reviewCount 公開レビューの件数
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
        Map<String, Integer> tagScores,
        Double averageRating,
        long reviewCount
) {
    /**
     * この銘柄の特徴の点数を、比較カードの香り・甘さ・飲み口の表示に変換する。
     */
    public java.util.List<com.example.demo.dto.TastePresentation.Indicator> tasteIndicators() {
        return com.example.demo.dto.TastePresentation.indicators(tagScores);
    }
}
