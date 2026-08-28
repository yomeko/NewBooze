package com.example.demo.model;

import java.util.Map;

/**
 * DB未接続段階の画面表示・推薦計算用の暫定モデル（＝いわゆる「デュアルモデル問題」の一方）。
 * entity.Sake（JPA Entity、DBの sake テーブルに対応）とは別物であり、
 * SakeCatalogServiceが保持するインメモリのダミーデータを表現するために使う。
 *
 * 濵田担当のデータ収集完了後、検索機能をentity.Sake + SakeRepositoryベースへ
 * 移行するタイミングで、このクラスの利用箇所は順次置き換えていく想定
 * （内部設計書 第8章「今後の課題」／「デュアルモデル問題」参照）。
 *
 * @param id          地酒ID（インメモリ用の暫定ID）
 * @param name        銘柄名
 * @param type        酒種（純米大吟醸・吟醸 等）
 * @param region      産地
 * @param abv         アルコール度数(%)
 * @param price       価格(円)
 * @param description 説明文
 * @param tagScores   タグ名→強さスコアのマップ（コサイン類似度計算での特徴ベクトルとして使用）
 */
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
