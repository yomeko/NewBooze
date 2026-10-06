package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 飲酒投稿の入力値（お酒の名前と感想）を受け取る入れ物。
 * {@code @NotBlank}で名前の空欄を、@Sizeで名前や感想の長すぎる入力を検出する。
 * {@code @Getter}と@Setterは、値を読む・設定するメソッドをLombokが自動で作る指定。
 */
@Getter @Setter
public class DrinkPostForm {
    @NotBlank(message = "お酒の名前を入力してください")
    @Size(max = 100, message = "お酒の名前は100文字以内で入力してください")
    private String sakeName;

    @Size(max = 500, message = "感想は500文字以内で入力してください")
    private String comment;
}
