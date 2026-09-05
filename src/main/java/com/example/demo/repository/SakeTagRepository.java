package com.example.demo.repository;

import com.example.demo.entity.SakeTag;
import com.example.demo.entity.SakeTagId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface SakeTagRepository extends JpaRepository<SakeTag, SakeTagId> {
    // RecommendService が、各地酒の特徴ベクトル（タグ別スコア一覧）を取得する際に使用
    @EntityGraph(attributePaths = "tag")
    List<SakeTag> findByIdSakeId(Long sakeId);

    // 複数銘柄をまとめて取得したい場合（一覧表示時にN+1を避けるため）
    @EntityGraph(attributePaths = "tag")
    List<SakeTag> findByIdSakeIdIn(List<Long> sakeIds);
}
