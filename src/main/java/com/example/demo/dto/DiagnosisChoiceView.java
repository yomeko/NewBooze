package com.example.demo.dto;

/**
 * S02（診断画面）表示用の選択肢DTO。
 * DBのDiagnosisChoiceエンティティから、画面表示に必要な項目だけを抜き出したもの。
 * Thymeleaf側は record のアクセサ(id(), text())を "choice.id" "choice.text" の形で参照する。
 */
public record DiagnosisChoiceView(Long id, String text) {
}
