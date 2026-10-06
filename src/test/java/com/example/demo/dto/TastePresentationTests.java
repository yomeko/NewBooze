package com.example.demo.dto;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 特徴の点数から作る棒グラフ、比較表示、おすすめ理由を確認するテスト。
 * 入力と期待する結果を並べ、点数がない場合や同点の場合も確認する。
 */
class TastePresentationTests {
    // 最高点が同じ特徴を両方強調し、0点以下を除き、棒の割合を最大点から計算することを確認する。
    @Test void distinguishesStrongestTiesFromSmallerAndZeroScores() {
        var result = TastePresentation.tendencies(Map.of("辛口", 2, "甘口", 8,
                "フルーティー", 8, "軽快", 0, "濃醇", -1));
        assertThat(result).hasSize(3);
        assertThat(result.stream().filter(TastePresentation.Tendency::strongest).map(TastePresentation.Tendency::name))
                .containsExactlyInAnyOrder("甘口", "フルーティー");
        assertThat(result.getLast().name()).isEqualTo("辛口");
        assertThat(result.getLast().relativeStrength()).isEqualTo(25);
        assertThat(TastePresentation.tendencies(Map.of("軽快", 0))).isEmpty();
    }

    // 必要な特徴がない場合は、数値を付けず未登録と表示することを確認する。
    @Test void missingTagsRemainUnknownRatherThanInventingSensoryMeasurements() {
        var result = TastePresentation.indicators(Map.of("旨口", 4));
        assertThat(result).extracting(TastePresentation.Indicator::label).containsExactly("香り", "甘さ", "飲み口");
        assertThat(result).allMatch(indicator -> indicator.level() == null && indicator.description().equals("未登録"));
    }

    // 強い方の特徴名を表示し、同点なら両方の名前を残すことを確認する。
    @Test void comparisonUsesDominantRegisteredTagAndPreservesTies() {
        var result = TastePresentation.indicators(Map.of("フルーティー", 4, "甘口", 2, "辛口", 5,
                "軽快", 3, "濃醇", 3));
        assertThat(result.get(0).level()).isEqualTo(4);
        assertThat(result.get(1).description()).isEqualTo("辛口");
        assertThat(result.get(1).level()).isEqualTo(5);
        assertThat(result.get(2).description()).isEqualTo("軽快・濃醇");
    }

    // 双方にある正の特徴から、点数の積が大きい順におすすめ理由を選ぶことを確認する。
    @Test void recommendationReasonsFollowSharedContributionAndExcludeNonmatches() {
        var result = TastePresentation.matchingTags(Map.of("甘口", 8, "フルーティー", 4, "辛口", 0, "酸味", 3),
                Map.of("甘口", 1, "フルーティー", 5, "辛口", 5, "軽快", 4));
        assertThat(result).containsExactly("フルーティー", "甘口");
        assertThat(TastePresentation.matchingTags(Map.of("甘口", 4), Map.of("辛口", 5))).isEmpty();
    }
}
