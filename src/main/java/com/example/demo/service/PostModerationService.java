package com.example.demo.service;

import com.example.demo.repository.DrinkPostRepository;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class PostModerationService {
    private static final List<String> BLOCKED_TERMS = List.of(
            "死ね", "しね", "殺す", "クズ", "ごみ", "バカ", "アホ", "キモい");
    private final DrinkPostRepository posts;
    public PostModerationService(DrinkPostRepository posts) { this.posts = posts; }

    public String validate(Long userId, String sakeName, String comment) {
        String combined = normalize(sakeName + " " + comment);
        if (BLOCKED_TERMS.stream().anyMatch(combined::contains))
            return "他の人を傷つける表現や不適切な言葉は投稿できません";
        LocalDateTime now = LocalDateTime.now();
        if (posts.existsByUserIdAndSakeNameIgnoreCaseAndCommentAndCreatedAtAfter(
                userId, sakeName, comment, now.minusHours(24)))
            return "同じ内容は24時間以内に再投稿できません";
        if (posts.countByUserIdAndCreatedAtAfter(userId, now.minusMinutes(10)) >= 5)
            return "短時間の大量投稿を防ぐため、10分後にもう一度お試しください";
        return null;
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.JAPANESE)
                .replaceAll("[\\s・ー_.,!！?？-]", "");
    }
}
