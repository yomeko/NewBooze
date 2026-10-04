package com.example.demo.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminSakeForm {
    @NotBlank(message = "銘柄名を入力してください") @Size(max = 100)
    private String name;
    @NotNull(message = "酒種を選択してください")
    private Long sakeTypeId;
    @Size(max = 50) private String region;
    @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 1)
    private BigDecimal abv;
    @Min(0) private Integer price;
    @Size(max = 10000) private String description;
}
