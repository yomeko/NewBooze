package com.example.demo.dto;

import java.util.List;

/**
 * S02（診断画面）表示用の設問DTO。
 * DBのDiagnosisQuestionエンティティ＋紐づくDiagnosisChoice一覧を、
 * 画面表示に必要な形にまとめたもの。
 */
public record DiagnosisQuestionView(Long id, String text, List<DiagnosisChoiceView> choices) {
}
