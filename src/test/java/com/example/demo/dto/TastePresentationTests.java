package com.example.demo.dto;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TastePresentationTests {
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

    @Test void missingTagsRemainUnknownRatherThanInventingSensoryMeasurements() {
        var result = TastePresentation.indicators(Map.of("旨口", 4));
        assertThat(result).extracting(TastePresentation.Indicator::label).containsExactly("香り", "甘さ", "飲み口");
        assertThat(result).allMatch(indicator -> indicator.level() == null && indicator.description().equals("未登録"));
    }

    @Test void comparisonUsesDominantRegisteredTagAndPreservesTies() {
        var result = TastePresentation.indicators(Map.of("フルーティー", 4, "甘口", 2, "辛口", 5,
                "軽快", 3, "濃醇", 3));
        assertThat(result.get(0).level()).isEqualTo(4);
        assertThat(result.get(1).description()).isEqualTo("辛口");
        assertThat(result.get(1).level()).isEqualTo(5);
        assertThat(result.get(2).description()).isEqualTo("軽快・濃醇");
    }

    @Test void recommendationReasonsFollowSharedContributionAndExcludeNonmatches() {
        var result = TastePresentation.matchingTags(Map.of("甘口", 8, "フルーティー", 4, "辛口", 0, "酸味", 3),
                Map.of("甘口", 1, "フルーティー", 5, "辛口", 5, "軽快", 4));
        assertThat(result).containsExactly("フルーティー", "甘口");
        assertThat(TastePresentation.matchingTags(Map.of("甘口", 4), Map.of("辛口", 5))).isEmpty();
    }
}
