package com.example.demo.service;

import com.example.demo.dto.SakePageDto;
import com.example.demo.model.Sake;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SakeCatalogService {
    private static final int PAGE_SIZE = 6;

    private final List<Sake> sakes = List.of(
            sake(1, "獺祭 純米大吟醸45", "純米大吟醸", "山口県", 16.0, 2180, "華やかな香りと、透明感のあるやわらかな甘みが特徴です。", "フルーティー", 5, "甘口", 4, "軽快", 3),
            sake(2, "新政 No.6", "生酒", "秋田県", 13.0, 2500, "爽やかな酸味とみずみずしい口当たりを楽しめる生酒です。", "酸味", 5, "フルーティー", 4, "軽快", 4),
            sake(3, "十四代 本丸", "本醸造", "山形県", 15.0, 3200, "やさしい旨みと上品な甘みが広がる、なめらかな味わいです。", "甘口", 5, "旨口", 4, "フルーティー", 3),
            sake(4, "黒龍 いっちょらい", "吟醸", "福井県", 15.0, 1650, "すっきりとした切れ味と、穏やかな吟醸香のバランスが魅力です。", "辛口", 4, "軽快", 5, "フルーティー", 2),
            sake(5, "而今 特別純米", "特別純米", "三重県", 16.0, 2800, "果実感のある香りに、米のふくらみと心地よい余韻が続きます。", "フルーティー", 4, "旨口", 4, "酸味", 3),
            sake(6, "田酒 特別純米", "特別純米", "青森県", 16.0, 1900, "米の旨みをしっかり感じられる、落ち着いた食中酒です。", "旨口", 5, "辛口", 3, "濃醇", 4),
            sake(7, "出羽桜 桜花吟醸", "吟醸", "山形県", 15.0, 1500, "華やかな香りと軽やかな飲み口で、日本酒入門にもおすすめです。", "フルーティー", 5, "軽快", 4, "甘口", 3),
            sake(8, "八海山 特別本醸造", "特別本醸造", "新潟県", 15.5, 1400, "淡麗でキレが良く、料理に寄り添うすっきりした味わいです。", "辛口", 5, "軽快", 4, "旨口", 2),
            sake(9, "風の森 秋津穂", "純米酒", "奈良県", 17.0, 1600, "微発泡感と鮮やかな酸味を持つ、瑞々しい純米酒です。", "酸味", 5, "フルーティー", 3, "軽快", 4),
            sake(10, "鍋島 特別純米", "特別純米", "佐賀県", 15.0, 1800, "ジューシーな甘みとほどよい酸味を備えた、親しみやすい一本です。", "甘口", 4, "酸味", 4, "旨口", 3)
    );

    public SakePageDto search(String keyword, String type, String region, int requestedPage) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Sake> filtered = sakes.stream().filter(sake ->
                (normalizedKeyword.isBlank() || contains(sake, normalizedKeyword))
                        && (type == null || type.isBlank() || sake.type().equals(type))
                        && (region == null || region.isBlank() || sake.region().equals(region)))
                .toList();
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        int from = Math.min(page * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        return new SakePageDto(filtered.subList(from, to), page, totalPages, filtered.size());
    }

    public Optional<Sake> findById(long id) { return sakes.stream().filter(sake -> sake.id() == id).findFirst(); }
    public List<Sake> featured() { return sakes.subList(0, 5); }
    public List<Sake> all() { return sakes; }
    public List<String> types() { return sakes.stream().map(Sake::type).distinct().sorted().toList(); }
    public List<String> regions() { return sakes.stream().map(Sake::region).distinct().sorted().toList(); }

    private boolean contains(Sake sake, String keyword) {
        return (sake.name() + " " + sake.type() + " " + sake.region() + " " + sake.description()).toLowerCase(Locale.ROOT).contains(keyword);
    }
    private static Sake sake(long id, String name, String type, String region, double abv, int price, String description, Object... tags) {
        Map<String, Integer> scores = new java.util.LinkedHashMap<>();
        for (int index = 0; index < tags.length; index += 2) scores.put((String) tags[index], (Integer) tags[index + 1]);
        return new Sake(id, name, type, region, abv, price, description, Map.copyOf(scores));
    }
}
