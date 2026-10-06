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

    private final User user;

    public CustomUserDetails(User user) {
        this.user = user;
    }

    /**
     * 一般ユーザーにはROLE_USER、管理者には追加でROLE_ADMINという権限名を返す。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return user.isAdmin()
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /**
     * 照合用のハッシュ値を返す。入力したパスワードそのものを返すメソッドではない。
     */
    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    /**
     * ログインIDとして使うemail列の値を返す。表示名はgetNameで取得する。
     */
    @Override
    public String getUsername() {
        // Spring Securityの「username」概念にはメールアドレスを割り当てる
        return user.getEmail();
    }

    // 現在は有効期限・ロック・停止を管理していないため、各状態を常に有効として返す。
    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }

    /**
     * DBの本人のユーザーIDを返す。ControllerはこのIDで保存先を特定する。
     */
    public Long getUserId() {
        return user.getId();
    }

    /**
     * 画面に表示するユーザーの名前を返す。
     */
    public String getName() {
        return user.getName();
    }

    /**
     * 仮パスワード利用中かを返し、ログイン後の案内先や画面の注意文に使う。
     */
    public boolean isTemporaryPassword() {
        return user.isTemporaryPassword();
    }
}
