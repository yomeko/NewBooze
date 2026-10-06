package com.example.demo.model;

import java.util.List;

/**
 * DBを使わなかった初期実装の、質問文と選択肢一覧をまとめたデータ。
 * 現在の診断処理はDBから質問を取得し、dto.DiagnosisQuestionViewを画面へ渡す。
 *
 * @param id      設問ID（インメモリ用の暫定ID）
 * @param text    設問文
 * @param choices 選択肢一覧
 */
public record DiagnosisQuestion(int id, String text, List<DiagnosisChoice> choices) {
}
