package com.example.demo.model;

import java.util.Map;

/**
 * DBを使わなかった初期実装の、選択肢と特徴ごとの加点をまとめたデータ。
 * 現在の診断はentity.DiagnosisChoiceとdto.DiagnosisChoiceViewを使う。
 * この型は現在の診断処理からは呼び出されていない。
 *
 * @param id         選択肢ID（インメモリ用の暫定ID）
 * @param text       選択肢の表示文言
 * @param tagWeights 選択時に加算されるタグ名→重みのマップ
 */
public record DiagnosisChoice(int id, String text, Map<String, Integer> tagWeights) {
}
