package com.example.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新規登録フォームの表示名、メールアドレス、パスワードを受け取る入れ物。
 * AuthControllerの@Validが、各項目の@NotBlank・@Email・@Sizeに従って入力を確認する。
 * 確認結果はHTMLのth:errorsで表示され、正しい入力ならUserに移して保存する。
 */
public class SignupForm {

    @NotBlank(message = "表示名を入力してください")
    @Size(max = 50, message = "表示名は50文字以内で入力してください")
    private String name;

    @NotBlank(message = "メールアドレスを入力してください")
    @Email(message = "メールアドレスの形式が正しくありません")
    @Size(max = 255, message = "メールアドレスは255文字以内で入力してください")
    private String email;

    @NotBlank(message = "パスワードを入力してください")
    @Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
    private String password;

    // getで値を読み、setで入力値を設定する。フォームとJavaの値を結び付けるために使う。
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
