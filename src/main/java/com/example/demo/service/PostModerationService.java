package com.example.demo.service;

import com.example.demo.repository.DrinkPostRepository;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 飲酒投稿に不適切な言葉、同じ内容の再投稿、短時間の大量投稿がないかを確認する。
 * 問題がある場合は画面に出す説明文を返し、問題がなければnullを返す。
 */
@Service
public class PostModerationService {
    private static final List<String> BLOCKED_TERMS = List.of(
            "死ね", "しね", "殺す", "クズ", "ごみ", "バカ", "アホ", "キモい");
    private final DrinkPostRepository posts;
    public PostModerationService(DrinkPostRepository posts) { this.posts = posts; }

    /**
     * 銘柄名と感想を確認する。禁止語、24時間以内の同内容、10分以内に5件以上の順で調べる。
     */
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

    // 全角・半角をそろえ、空白や一部の記号を除いて禁止語の表記の違いを減らす。
    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.JAPANESE)
                .replaceAll("[\\s・ー_.,!！?？-]", "");
    }
}
