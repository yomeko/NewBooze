package com.example.demo.repository;

import com.example.demo.entity.UserProfileImage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ユーザーIDを使ってプロフィール画像を保存・取得・削除するためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface UserProfileImageRepository extends JpaRepository<UserProfileImage, Long> {
}
