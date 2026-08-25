package com.example.demo.repository;

import com.example.demo.entity.ChoiceTag;
import com.example.demo.entity.ChoiceTagId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChoiceTagRepository extends JpaRepository<ChoiceTag, ChoiceTagId> {
    // ある選択肢が選ばれた際に、加算すべきタグ・重みの一覧を取得する
    // （id.choiceId は @EmbeddedId のプロパティ経由でのネストしたパスの意味）
    List<ChoiceTag> findByIdChoiceId(Long choiceId);
}
