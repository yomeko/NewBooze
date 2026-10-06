package com.example.demo.dto;

import java.util.List;

/**
 * 診断画面へ渡す、質問の番号・質問文・選択肢一覧をまとめたデータ。
 * DBの情報から表示に必要な項目だけを取り出すため、HTMLはDBの保存形式を意識せず使える。
 */
public record DiagnosisQuestionView(Long id, String text, List<DiagnosisChoiceView> choices) {
}
