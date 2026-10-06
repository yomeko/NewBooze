package com.example.demo.repository;

import com.example.demo.entity.ChoiceTag;
import com.example.demo.entity.ChoiceTagId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 診断の選択肢から、加点する特徴と点数を取り出すためのDB操作窓口。
 * JpaRepositoryを継承すると、save（保存）やfindById（IDで検索）などをSpringが用意する。
 * findBy...などのメソッドは名前から検索条件を組み立て、@Queryがある場合は指定した検索文を使う。
 */
public interface ChoiceTagRepository extends JpaRepository<ChoiceTag, ChoiceTagId> {
    // ある選択肢が選ばれた際に、加算すべきタグ・重みの一覧を取得する
    // （id.choiceId は @EmbeddedId のプロパティ経由でのネストしたパスの意味）
    List<ChoiceTag> findByIdChoiceId(Long choiceId);
}
