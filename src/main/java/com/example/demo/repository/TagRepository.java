package com.example.demo.repository;

import com.example.demo.entity.Tag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 特徴名を保存し、「味わい」「香り」などの分類で検索するためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface TagRepository extends JpaRepository<Tag, Long> {
    // 指定した分類の特徴を取り出すためのメソッド。例：香りに属する特徴の一覧。
    List<Tag> findByCategory(String category);
}
