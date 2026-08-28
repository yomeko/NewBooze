package com.example.demo.model;

import java.util.List;

/**
 * DB未接続だった開発初期段階で使用していた、インメモリ用の診断設問モデル。
 * model.DiagnosisChoice同様、現在はDiagnosisServiceのDB連携版に置き換わり未使用。
 *
 * @param id      設問ID（インメモリ用の暫定ID）
 * @param text    設問文
 * @param choices 選択肢一覧
 */
public record DiagnosisQuestion(int id, String text, List<DiagnosisChoice> choices) {
}
