package com.example.demo.dto;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 特徴ごとの点数を、好みの棒グラフ・5段階の表示・おすすめ理由へ変換する。
 * タグは「甘口」「軽快」などの特徴名。ここで表示する強さは登録された点数に基づく。
 * staticメソッドだけを使うため、このクラス自体のインスタンスを作る必要はない。
 */
public final class TastePresentation {
    private TastePresentation() {}

    // 棒グラフ用の特徴名、点数、最大点に対する割合、一番強い特徴かどうか。
    public record Tendency(String name, int score, int relativeStrength, boolean strongest) {}
    // 比較カード用の項目名・説明・5段階の強さ。level=nullは特徴が未登録の意味。
    public record Indicator(String label, String description, Integer level) {}

    public record Quadrant(String name, String description, int x, int y) {}

    /** 回答タグからの相対位置。日本酒度・酸度の実測値には換算しない。 */
    public static Quadrant quadrant(Map<String, Integer> scores) {
        int sweet = Math.max(0, scores.getOrDefault("甘口", 0));
        int dry = Math.max(0, scores.getOrDefault("辛口", 0));
        int light = Math.max(0, scores.getOrDefault("軽快", 0));
        int rich = Math.max(0, scores.getOrDefault("濃醇", 0));
        String body = rich == light ? "濃淡は中間" : rich > light ? "濃醇" : "淡麗";
        String taste = sweet == dry ? "甘辛は中間" : sweet > dry ? "甘口" : "辛口";
        String name = rich == light || sweet == dry ? body + "・" + taste : body + taste;
        return new Quadrant(name, "甘さと飲みごたえの回答から見た、今の好みの目安です。",
                axis(dry, sweet), axis(light, rich));
    }

    private static int axis(int positive, int negative) {
        return positive + negative == 0 ? 50 : 50 + (int) Math.round(40.0 * (positive - negative) / (positive + negative));
    }

    public static String termDescription(String name) {
        return switch (name) {
            case "甘口" -> "甘みを感じやすい味わい。糖分だけでなく、酸味などとのバランスでも印象が変わります。";
            case "辛口" -> "甘みを控えめに感じる味わい。唐辛子のような辛さという意味ではありません。";
            case "軽快", "淡麗" -> "さらりとした、軽やかな飲み心地を表します。";
            case "濃醇" -> "コクや味のふくらみがある、飲みごたえのある味わいです。";
            case "旨口" -> "うまみを感じる味わい。甘口・辛口とは別の特徴です。";
            case "酸味" -> "爽やかさや味の輪郭をつくる酸の味。酸味の強さだけで濃淡は決まりません。";
            case "フルーティー" -> "果物を思わせる香り。果汁入りという意味ではなく、甘口とも限りません。";
            default -> "日本酒の香りや味わいを表す特徴です。";
        };
    }

    /**
     * 正の点数の特徴を強い順に並べ、一番高い点数を100%として棒の長さを計算する。
     * 例えば最大が10点なら5点は50%。最大点が同じ特徴はすべて「強い傾向」にする。
     */
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

    /**
     * 香り・甘さ・飲み口の3項目を、銘柄比較カードで共通して使う表示へ変換する。
     */
    public static List<Indicator> indicators(Map<String, Integer> scores) {
        return List.of(single("香り", "フルーティー", scores),
                paired("甘さ", "甘口", "辛口", scores),
                paired("飲み口", "軽快", "濃醇", scores));
    }

    /**
     * 1つの特徴の点数を最大5段階で表示する。点数がなければ未登録として扱う。
     */
    private static Indicator single(String label, String tag, Map<String, Integer> scores) {
        int value = scores.getOrDefault(tag, 0);
        return value > 0 ? new Indicator(label, tag, Math.min(5, value))
                : new Indicator(label, "未登録", null);
    }

    /**
     * 甘口と辛口など2つの特徴を比較し、強い方の名前と最大5段階の強さを返す。
     * 同点なら両方の名前を表示し、両方0なら未登録にする。
     */
    private static Indicator paired(String label, String first, String second, Map<String, Integer> scores) {
        int a = Math.max(0, scores.getOrDefault(first, 0));
        int b = Math.max(0, scores.getOrDefault(second, 0));
        if (a == 0 && b == 0) return new Indicator(label, "未登録", null);
        String description = a == b ? first + "・" + second : a > b ? first : second;
        return new Indicator(label, description, Math.min(5, Math.max(a, b)));
    }

    /**
     * 好みと銘柄の両方で点数が正の特徴だけを、おすすめ理由として最大2件選ぶ。
     * 双方の点数を掛けた値が大きい特徴から選ぶ。
     */
    public static List<String> matchingTags(Map<String, Integer> preferences, Map<String, Integer> features) {
        return preferences.entrySet().stream()
                .filter(entry -> entry.getValue() > 0 && features.getOrDefault(entry.getKey(), 0) > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingLong(entry ->
                        (long) entry.getValue() * features.get(entry.getKey())).reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(2).map(Map.Entry::getKey).toList();
    }
}
