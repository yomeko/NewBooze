package com.example.demo.service;

import com.example.demo.dto.TastePresentation;
import com.example.demo.model.Sake;
import java.io.IOException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** ホームの1銘柄を抽選し、編集者が指定したメジャー銘柄とタグの近さを比較する。 */
@Service
public class SakeDiscoveryService {
    private final SakeCatalogService catalog;
    private final Map<String, MajorReference> references;

    public SakeDiscoveryService(SakeCatalogService catalog, ObjectMapper mapper,
            @Value("${app.major-sake:classpath:data/major-sake.json}") Resource source) throws IOException {
        this.catalog = catalog;
        try (var input = source.getInputStream()) {
            references = java.util.Arrays.stream(mapper.readValue(input, MajorReference[].class))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(MajorReference::name, ref -> ref));
        }
    }

    public Optional<Discovery> discover() {
        List<Sake> all = catalog.all();
        if (all.isEmpty()) return Optional.empty();
        Sake selected = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        return Optional.of(new Discovery(selected, similarMajor(selected, all).orElse(null)));
    }

    /** 同一銘柄を除外し、正の共通タグがある候補のコサイン類似度を比較。同点はID順。 */
    Optional<MajorMatch> similarMajor(Sake selected, List<Sake> all) {
        return all.stream().filter(sake -> sake.id() != selected.id() && references.containsKey(sake.name()))
                .map(sake -> new MajorMatch(sake, similarity(selected.tagScores(), sake.tagScores()),
                        TastePresentation.matchingTags(selected.tagScores(), sake.tagScores()), references.get(sake.name())))
                .filter(match -> match.score() > 0 && !match.matchingTags().isEmpty())
                .sorted(Comparator.comparingDouble(MajorMatch::score).reversed()
                        .thenComparingLong(match -> match.sake().id()))
                .findFirst();
    }

    private static double similarity(Map<String, Integer> left, Map<String, Integer> right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (var entry : left.entrySet()) {
            double value = Math.max(0, entry.getValue());
            leftNorm += value * value;
            dot += value * Math.max(0, right.getOrDefault(entry.getKey(), 0));
        }
        for (int raw : right.values()) {
            double value = Math.max(0, raw);
            rightNorm += value * value;
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    public List<MajorReference> majorReferences() {
        return references.values().stream().sorted(Comparator.comparing(MajorReference::name)).toList();
    }

    public record Discovery(Sake sake, MajorMatch similar) {}
    public record MajorMatch(Sake sake, double score, List<String> matchingTags, MajorReference reference) {}
    public record MajorReference(String name, String reason, String productUrl, String retailerUrl, String checkedOn) {
        public MajorReference {
            if (name == null || name.isBlank() || reason == null || reason.isBlank()
                    || checkedOn == null || checkedOn.isBlank())
                throw new IllegalArgumentException("メジャー銘柄の名称・根拠・確認日が必要です");
            for (String url : List.of(productUrl, retailerUrl)) {
                URI uri = URI.create(url);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IllegalArgumentException("根拠リンクにはHTTPSのURLを指定してください");
            }
        }
    }
}
