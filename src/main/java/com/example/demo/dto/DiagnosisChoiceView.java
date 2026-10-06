package com.example.demo.dto;

/**
 * 診断画面へ渡す、選択肢の番号と表示文だけをまとめたデータ。
 * recordは値をまとめるJavaの書き方で、id()やtext()などの読み取りメソッドが自動で作られる。
 */
public record DiagnosisChoiceView(Long id, String text) {
}
