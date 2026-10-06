package com.example.demo.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 管理画面で入力された日本酒の登録情報を受け取る入れ物。
 * DTOは画面と処理の間でデータを渡すための型。これ自体はDBへ保存する型ではない。
 * {@code @NotBlank}は空欄禁止、@Sizeは文字数、@Minなどは数値の範囲を確認する指定。
 */
@Getter
@Setter
public class AdminSakeForm {
    @NotBlank(message = "銘柄名を入力してください") @Size(max = 100)
    private String name;
    // 酒種の名前ではなくIDを受け取り、ControllerでDBに存在する酒種か確認する。
    @NotNull(message = "酒種を選択してください")
    private Long sakeTypeId;
    @Size(max = 50) private String region;
    @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 1)
    private BigDecimal abv;
    @Min(0) private Integer price;
    @Size(max = 10000) private String description;
    private org.springframework.web.multipart.MultipartFile image;
}
