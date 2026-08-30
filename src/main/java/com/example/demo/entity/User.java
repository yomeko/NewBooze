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
 * アカウント情報（users）。
 * S07（ログイン／新規登録）実装時にこのEntityを利用する想定。
 * password_hash には平文パスワードを絶対に入れないこと（外部設計書 8.2 セキュリティ要件）。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /** ハッシュ化済みパスワード。ハッシュアルゴリズムの選定はS07実装時に確定する（要確認事項）。 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 仮パスワードでログイン中ならtrue。変更完了後にfalseに戻す。 */
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
