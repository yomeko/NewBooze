package com.example.demo.repository;

import com.example.demo.entity.UserPreference;
import com.example.demo.entity.UserPreferenceId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, UserPreferenceId> {
    // RecommendService が、あるユーザーの嗜好ベクトル（タグ別スコア一覧）を取得する際に使用
    List<UserPreference> findByIdUserId(Long userId);

    // upsert（存在すれば更新、無ければ新規作成）判定のための単一取得
    Optional<UserPreference> findByIdUserIdAndIdTagId(Long userId, Long tagId);
}
