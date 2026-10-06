package com.example.demo.repository;

import com.example.demo.entity.User;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * アカウントを保存し、ログインIDや表示名でユーザーを探すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface UserRepository extends JpaRepository<User, Long> {
    // 名前の一部で検索する。IgnoreCaseは大文字・小文字を区別せず、Pageでページ情報も返す指定。
    org.springframework.data.domain.Page<User> findByNameContainingIgnoreCase(String name, org.springframework.data.domain.Pageable pageable);

    // S07のログイン処理（email + password_hash照合）で使用
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
    // プロフィール変更時、自分以外に同じメールアドレスのユーザーがいるか確認する。
    boolean existsByEmailAndIdNot(String email, Long id);
    List<User> findByIdNotOrderByNameAsc(Long id);
}
