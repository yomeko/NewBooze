package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * データベースのusersという表の1件を、Javaで扱うためのクラス。
 * 表示名、ログインID、パスワードの照合用データ、管理者かどうかを持つ。
 * {@code @Entity}はDBの表に対応する指定、@Tableはその表の名前を示す。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    // 主キー（この表の1件を区別する番号）。IDの採番はDBの自動連番に任せる。
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    // 未設定のnullを許さず、同じログインIDを複数のアカウントに登録できないようにする。
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /** BCryptでハッシュ化したパスワード。入力されたパスワードを照合するための値。 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    // trueなら管理者。CustomUserDetailsで管理画面に入る権限へ変換する。
    @Column(nullable = false)
    private boolean admin;

    /** 仮パスワードを発行済みならtrue。通常のパスワードへ変更するとfalseに戻す。 */
    @Column(name = "temporary_password", nullable = false)
    private boolean temporaryPassword;

    /**
     * Lombokのアノテーション処理が無効なIDEでも、仮パスワード状態を
     * 更新できるようにsetterを明示的に定義する。
     */
    public void setTemporaryPassword(boolean temporaryPassword) {
        this.temporaryPassword = temporaryPassword;
    }

    // DB側で DEFAULT CURRENT_TIMESTAMP が設定されているため、
    // アプリ側からは insertable/updatable = false にして値を渡さないようにする。
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
