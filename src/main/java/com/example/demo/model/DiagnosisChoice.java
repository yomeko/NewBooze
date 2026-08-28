package com.example.demo.model;

import java.util.Map;

/**
 * DB未接続だった開発初期段階で使用していた、インメモリ用の診断選択肢モデル。
 * entity.DiagnosisChoice（JPA Entity）・dto.DiagnosisChoiceView（画面表示用DTO）とは別物。
 * 現在はDiagnosisServiceがDB連携版（choice_tagsテーブル参照）に移行済みのため未使用だが、
 * 過去の実装経緯を残すため当面削除せずに残している。
 *
 * @param id         選択肢ID（インメモリ用の暫定ID）
 * @param text       選択肢の表示文言
 * @param tagWeights 選択時に加算されるタグ名→重みのマップ
 */
public record DiagnosisChoice(int id, String text, Map<String, Integer> tagWeights) {
}
