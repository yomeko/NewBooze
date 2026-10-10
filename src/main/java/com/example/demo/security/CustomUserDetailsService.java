package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * ログインフォームのusername（メールアドレスまたは管理者ID）でユーザーを探す。
 * 見つかったユーザーをCustomUserDetailsに包んで返す。
 * パスワードの照合は、この後にSpring SecurityがPasswordEncoderを使って行う。
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public CustomUserDetailsService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 入力されたログインIDに対応するユーザーを取得する。存在しなければログイン失敗にする。
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("ログイン情報を確認してください"));
        // 旧版で作成済みの固定初期パスワードも、コード更新だけで利用を拒否する。
        if (user.isAdmin() && passwordEncoder.matches("admin", user.getPasswordHash())) {
            throw new UsernameNotFoundException("ログイン情報を確認してください");
        }
        return new CustomUserDetails(user);
    }
}
