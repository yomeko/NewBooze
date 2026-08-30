package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class DrinkPostForm {
    @NotBlank(message = "お酒の名前を入力してください")
    @Size(max = 100, message = "お酒の名前は100文字以内で入力してください")
    private String sakeName;

    @Size(max = 500, message = "感想は500文字以内で入力してください")
    private String comment;
}
