package com.example.demo.repository;

import com.example.demo.entity.Tag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {
    // 診断結果画面（S08）でタグ分類ごとに嗜好傾向を可視化する際などに利用
    List<Tag> findByCategory(String category);
}
