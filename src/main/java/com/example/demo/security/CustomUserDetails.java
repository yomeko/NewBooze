package com.example.demo.security;

import com.example.demo.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * DBのユーザー情報を、ログイン管理の仕組みが使える形に包むクラス。
 * ログインID、保存済みパスワード、管理者かどうかをSpring Securityに伝える。
 * 画面処理はここから本人のユーザーIDや表示名も取得する。
 */
public class CustomUserDetails implements UserDetails {

    // 認証時点の値を保持し、DB更新後も古いセッションの権限や認証情報を比較できるようにする。
    private final Long userId;
    private final String name;
    private final String email;
    private final String passwordHash;
    private final boolean admin;
    private final boolean temporaryPassword;
    private final boolean emailVerified;
    // 同じ不変ハッシュへの高コストな照合はセッション中に繰り返さない。復元時には再確認する。
    private transient volatile boolean legacyAdminPasswordChecked;

    public CustomUserDetails(User user) {
        this.userId = user.getId();
        this.name = user.getName();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.admin = user.isAdmin();
        this.temporaryPassword = user.isTemporaryPassword();
        this.emailVerified = user.isEmailVerified();
    }

    /**
     * 一般ユーザーにはROLE_USER、管理者には追加でROLE_ADMINという権限名を返す。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /**
     * 照合用のハッシュ値を返す。入力したパスワードそのものを返すメソッドではない。
     */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * ログインIDとして使うemail列の値を返す。表示名はgetNameで取得する。
     */
    @Override
    public String getUsername() {
        // Spring Securityの「username」概念にはメールアドレスを割り当てる
        return email;
    }

    // 有効期限・ロックは管理せず、ログインの可否だけメール認証状態で決める。
    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    // パスワードが一致しても、メール未認証の会員はSpring Securityがログインを拒否する。
    public boolean isEnabled() { return emailVerified; }

    /**
     * DBの本人のユーザーIDを返す。ControllerはこのIDで保存先を特定する。
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 画面に表示するユーザーの名前を返す。
     */
    public String getName() {
        return name;
    }

    /**
     * 仮パスワード利用中かを返し、ログイン後の案内先や画面の注意文に使う。
     */
    public boolean isTemporaryPassword() {
        return temporaryPassword;
    }

    boolean isLegacyAdminPasswordChecked() {
        return legacyAdminPasswordChecked;
    }

    void markLegacyAdminPasswordChecked() {
        legacyAdminPasswordChecked = true;
    }
}
