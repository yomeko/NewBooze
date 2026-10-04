package com.example.demo.dto;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 登録済みタグの強さを、診断結果と銘柄比較で共通の表示に変換する。 */
public final class TastePresentation {
    private TastePresentation() {}

    public record Tendency(String name, int score, int relativeStrength, boolean strongest) {}
    public record Indicator(String label, String description, Integer level) {}

    public static List<Tendency> tendencies(Map<String, Integer> scores) {
        int max = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max <= 0) return List.of();
        return scores.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new Tendency(entry.getKey(), entry.getValue(),
                        (int) Math.round(entry.getValue() * 100.0 / max), entry.getValue() == max))
                .toList();
    }

    public static List<Indicator> indicators(Map<String, Integer> scores) {
        return List.of(single("香り", "フルーティー", scores),
                paired("甘さ", "甘口", "辛口", scores),
                paired("飲み口", "軽快", "濃醇", scores));
    }

    private static Indicator single(String label, String tag, Map<String, Integer> scores) {
        int value = scores.getOrDefault(tag, 0);
        return value > 0 ? new Indicator(label, tag, Math.min(5, value))
                : new Indicator(label, "未登録", null);
    }

    private static Indicator paired(String label, String first, String second, Map<String, Integer> scores) {
        int a = Math.max(0, scores.getOrDefault(first, 0));
        int b = Math.max(0, scores.getOrDefault(second, 0));
        if (a == 0 && b == 0) return new Indicator(label, "未登録", null);
        String description = a == b ? first + "・" + second : a > b ? first : second;
        return new Indicator(label, description, Math.min(5, Math.max(a, b)));
    }

    /** 推薦計算への寄与が大きい共通タグを示す。ゼロ・負の特徴は一致理由にしない。 */
    public static List<String> matchingTags(Map<String, Integer> preferences, Map<String, Integer> features) {
        return preferences.entrySet().stream()
                .filter(entry -> entry.getValue() > 0 && features.getOrDefault(entry.getKey(), 0) > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingLong(entry ->
                        (long) entry.getValue() * features.get(entry.getKey())).reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(2).map(Map.Entry::getKey).toList();
    }
}
