package com.example.demo.service;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 商品写真の登録URLと公開利用の確認を分けて扱う。
 * DBの元URLは変更せず、台帳で個別に承認した写真だけを画面へ渡す。
 */
@Service
public class ProductImagePublicationService {
    private final Set<PhotoKey> approved;

    public ProductImagePublicationService(ObjectMapper mapper,
            @Value("classpath:data/product-image-publication.json") Resource source) throws IOException {
        Set<PhotoKey> loaded = new HashSet<>();
        Set<PhotoKey> seen = new HashSet<>();
        try (var input = source.getInputStream()) {
            for (PhotoApproval entry : mapper.readValue(input, PhotoApproval[].class)) {
                if (entry == null) continue;
                PhotoKey key = new PhotoKey(entry.sakeId(), entry.sakeName(), entry.imageUrl());
                if (!seen.add(key)) throw new IllegalArgumentException("商品写真の台帳に重複があります: " + entry.sakeId());
                if (canPublish(entry)) loaded.add(key);
            }
        }
        approved = Set.copyOf(loaded);
    }

    private ProductImagePublicationService() {
        approved = Set.of();
    }

    /** Springを使わない既存の単体テストでも、未確認写真を公開しない。 */
    static ProductImagePublicationService denyAll() {
        return new ProductImagePublicationService();
    }

    /** 銘柄ID・名前・URLが台帳と完全に一致する承認済み写真だけを返す。 */
    public String displayUrl(long sakeId, String sakeName, String storedUrl) {
        return storedUrl != null && approved.contains(new PhotoKey(sakeId, sakeName, storedUrl))
                ? storedUrl : null;
    }

    private static boolean canPublish(PhotoApproval entry) {
        if (!Boolean.TRUE.equals(entry.approved()) || entry.sakeId() == null || entry.sakeId() <= 0
                || !hasText(entry.sakeName())
                || !imageUrl(entry.imageUrl()) || !hasText(entry.rightsHolder())
                || !httpsUrl(entry.permissionSource(), true) || !hasText(entry.usageConditions())) return false;
        try {
            // 日付・利用根拠が欠けた承認指定は採用しない。許諾内容自体は運営者が確認する。
            return !LocalDate.parse(entry.permissionCheckedOn()).isAfter(LocalDate.now(ZoneId.of("Asia/Tokyo")));
        } catch (DateTimeParseException | NullPointerException exception) {
            return false;
        }
    }

    private static boolean imageUrl(String value) {
        if (!hasText(value) || !value.equals(value.strip())) return false;
        // ローカル保存も自動承認しない。承認済みの場合に限り、商品写真の保存場所を許可する。
        return httpsUrl(value, false)
                || value.matches("/images/sake/[A-Za-z0-9][A-Za-z0-9_-]*\\.(jpg|jpeg|png|webp|gif)")
                || value.matches("/sake/images/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)");
    }

    private static boolean httpsUrl(String value, boolean allowFragment) {
        if (!hasText(value) || !value.equals(value.strip())) return false;
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && (allowFragment || uri.getFragment() == null);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PhotoKey(Long sakeId, String sakeName, String imageUrl) {}

    /** sourcePageは写真の出典、permissionSourceは公開利用を認める根拠であり別の情報。 */
    public record PhotoApproval(Long sakeId, String sakeName, String imageUrl, String sourcePage,
            Boolean approved, String rightsHolder, String permissionSource,
            String permissionCheckedOn, String usageConditions) {}
}
