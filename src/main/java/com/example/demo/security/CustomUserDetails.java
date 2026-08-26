package com.example.demo.security;

import com.example.demo.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring SecurityのUserDetailsインタフェースを、
 * アプリ独自のUserエンティティでラップする実装クラス。
 * Spring Securityの認証処理は全てこの型を通して行われる。
 */
public class CustomUserDetails implements UserDetails {

    private final User user;

    public CustomUserDetails(User user) {
        this.user = user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 現時点では管理者/一般等の権限区分を設けないため、
        // 全ユーザー共通でROLE_USERのみ付与する
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        // Spring Securityの「username」概念にはメールアドレスを割り当てる
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }

    /** Controller側でDBのuser_idを取り出すためのヘルパーメソッド */
    public Long getUserId() {
        return user.getId();
    }

    public String getName() {
        return user.getName();
    }
}
