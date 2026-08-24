package com.example.demo.service;

import com.example.demo.model.DiagnosisChoice;
import com.example.demo.model.DiagnosisQuestion;
import com.example.demo.model.Sake;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DiagnosisService {
    private final SakeCatalogService catalogService;
    private final List<DiagnosisQuestion> questions;

    public DiagnosisService(SakeCatalogService catalogService) {
        this.catalogService = catalogService;
        questions = List.of(
                question(1, "まず、気分に近い一杯は？", choice(11, "果物のように華やかな香り", "フルーティー", 5), choice(12, "米のうまみをしっかり感じたい", "旨口", 5), choice(13, "すっきりシャープに飲みたい", "辛口", 5)),
                question(2, "口当たりの好みは？", choice(21, "軽やかでさらり", "軽快", 5), choice(22, "ふくよかで飲みごたえあり", "濃醇", 5), choice(23, "きゅっと爽やかな酸味", "酸味", 5)),
                question(3, "合わせたい場面は？", choice(31, "乾杯・プレゼント", "フルーティー", 3, "甘口", 2), choice(32, "食事とゆっくり", "旨口", 3, "辛口", 2), choice(33, "暑い日に冷やして", "軽快", 3, "酸味", 3)),
                question(4, "甘さの印象は？", choice(41, "やさしい甘みが好き", "甘口", 5), choice(42, "甘すぎないバランス派", "旨口", 3, "酸味", 2), choice(43, "キレのある辛口派", "辛口", 5))
        );
    }
    public List<DiagnosisQuestion> questions() { return questions; }
    public Map<String, Integer> preferenceFor(List<Integer> choiceIds) {
        Set<Integer> selected = Set.copyOf(choiceIds == null ? List.of() : choiceIds);
        Map<String, Integer> preferences = new LinkedHashMap<>();
        questions.stream().flatMap(question -> question.choices().stream()).filter(choice -> selected.contains(choice.id()))
                .forEach(choice -> choice.tagWeights().forEach((tag, weight) -> preferences.merge(tag, weight, Integer::sum)));
        return preferences;
    }
    public List<Recommendation> recommend(Map<String, Integer> preferences) {
        return catalogService.all().stream().map(sake -> new Recommendation(sake, cosine(preferences, sake.tagScores())))
                .sorted(Comparator.comparingDouble(Recommendation::score).reversed()).limit(5).toList();
    }
    private static double cosine(Map<String, Integer> preferences, Map<String, Integer> features) {
        if (preferences.isEmpty()) return 0;
        double dot = 0, preferenceNorm = 0, featureNorm = 0;
        for (int value : preferences.values()) preferenceNorm += value * value;
        for (int value : features.values()) featureNorm += value * value;
        for (Map.Entry<String, Integer> entry : preferences.entrySet()) dot += entry.getValue() * features.getOrDefault(entry.getKey(), 0);
        return dot / (Math.sqrt(preferenceNorm) * Math.sqrt(featureNorm));
    }
    private static DiagnosisQuestion question(int id, String text, DiagnosisChoice... choices) { return new DiagnosisQuestion(id, text, List.of(choices)); }
    private static DiagnosisChoice choice(int id, String text, Object... weights) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (int index = 0; index < weights.length; index += 2) values.put((String) weights[index], (Integer) weights[index + 1]);
        return new DiagnosisChoice(id, text, values);
    }
    public record Recommendation(Sake sake, double score) { }
}
