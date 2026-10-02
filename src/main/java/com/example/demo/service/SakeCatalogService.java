package com.example.demo.service;

import com.example.demo.dto.SakePageDto;
import com.example.demo.model.Sake;
import com.example.demo.repository.SakeRepository;
import com.example.demo.repository.SakeTagRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed catalog used by search, detail, featured sake, and diagnosis. */
@Service
public class SakeCatalogService {
    private static final int PAGE_SIZE = 6;
    private static final int FEATURED_SIZE = 4;
    private static final Sort CATALOG_ORDER = Sort.by(Sort.Direction.ASC, "id");

    private final SakeRepository sakeRepository;
    private final SakeTagRepository sakeTagRepository;

    public SakeCatalogService(SakeRepository sakeRepository,
                              SakeTagRepository sakeTagRepository) {
        this.sakeRepository = sakeRepository;
        this.sakeTagRepository = sakeTagRepository;
    }

    @Transactional(readOnly = true)
    public SakePageDto search(String keyword, String name, String type, Integer minPrice, Integer maxPrice,
                              String taste, String sortOrder, int requestedPage) {
        String normalizedKeyword = emptyToNull(keyword);
        String normalizedName = emptyToNull(name);
        String normalizedType = emptyToNull(type);
        String normalizedTaste = emptyToNull(taste);
        // 半角・全角の#を除き、DBに保存されているタグ名に合わせる。
        if (normalizedKeyword != null && (normalizedKeyword.startsWith("#") || normalizedKeyword.startsWith("＃"))) {
            normalizedKeyword = emptyToNull(normalizedKeyword.substring(1));
            // 味わいが未指定ならタグ条件へ移し、商品説明などの部分一致検索を避ける。
            // 味わいが指定済みの場合はその条件を維持し、キーワードとの両方で絞り込む。
            if (normalizedTaste == null) {
                normalizedTaste = normalizedKeyword;
                normalizedKeyword = null;
            }
        }
        int pageNumber = Math.max(0, requestedPage);
        Page<com.example.demo.entity.Sake> page = findPage(
                normalizedKeyword, normalizedName, normalizedType, minPrice, maxPrice,
                normalizedTaste, sortOrder, pageNumber);
        if (page.getTotalPages() > 0 && pageNumber >= page.getTotalPages()) {
            pageNumber = page.getTotalPages() - 1;
            page = findPage(normalizedKeyword, normalizedName, normalizedType, minPrice, maxPrice,
                    normalizedTaste, sortOrder, pageNumber);
        }
        return new SakePageDto(mapAll(page.getContent()), pageNumber,
                Math.max(1, page.getTotalPages()), page.getTotalElements());
    }

    private Page<com.example.demo.entity.Sake> findPage(String keyword, String name, String type,
                                                         Integer minPrice, Integer maxPrice,
                                                         String taste, String sortOrder,
                                                         int pageNumber) {
        return sakeRepository.search(keyword, name, type, minPrice, maxPrice, taste,
                PageRequest.of(pageNumber, PAGE_SIZE, searchSort(sortOrder)));
    }

    /** 画面から受け取る値をホワイトリストで安全なSortに変換する。 */
    private Sort searchSort(String sortOrder) {
        return switch (sortOrder == null ? "recommended" : sortOrder) {
            case "ratingDesc" -> Sort.by(Sort.Order.desc("hasPublicReviews"), Sort.Order.desc("averageRating"), Sort.Order.desc("reviewCount"), Sort.Order.asc("id"));
            case "ratingAsc" -> Sort.by(Sort.Order.desc("hasPublicReviews"), Sort.Order.asc("averageRating"), Sort.Order.desc("reviewCount"), Sort.Order.asc("id"));
            case "reviewCount" -> Sort.by(Sort.Order.desc("reviewCount"), Sort.Order.desc("averageRating"), Sort.Order.asc("id"));
            case "priceAsc" -> Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id"));
            case "priceDesc" -> Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id"));
            case "newest" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            default -> CATALOG_ORDER;
        };
    }

    @Transactional(readOnly = true)
    public Optional<Sake> findById(long id) {
        return sakeRepository.findById(id).map(entity -> {
            Map<Long, Map<String, Integer>> tags = tagsBySakeId(List.of(entity.getId()));
            return toView(entity, tags.getOrDefault(entity.getId(), Map.of()));
        });
    }

    @Transactional(readOnly = true)
    public List<Sake> featured() {
        // IDだけを抽選し、選ばれた最大4件の詳細を取得する。
        List<Long> ids = new ArrayList<>(sakeRepository.findAllIds());
        Collections.shuffle(ids);
        List<Long> selected = ids.stream().limit(FEATURED_SIZE).toList();
        if (selected.isEmpty()) return List.of();
        var entities = new ArrayList<>(sakeRepository.findAllById(selected));
        entities.sort(java.util.Comparator.comparingInt(entity -> selected.indexOf(entity.getId())));
        return mapAll(entities);
    }

    @Transactional(readOnly = true)
    public List<Sake> all() {
        return mapAll(sakeRepository.findAll(CATALOG_ORDER));
    }

    @Transactional(readOnly = true)
    public List<String> types() {
        return sakeRepository.findDistinctTypeNames();
    }

    @Transactional(readOnly = true)
    public List<String> regions() {
        return sakeRepository.findDistinctRegions();
    }

    private List<Sake> mapAll(List<com.example.demo.entity.Sake> entities) {
        if (entities.isEmpty()) return List.of();
        Map<Long, Map<String, Integer>> tags = tagsBySakeId(
                entities.stream().map(com.example.demo.entity.Sake::getId).toList());
        return entities.stream()
                .map(entity -> toView(entity, tags.getOrDefault(entity.getId(), Map.of())))
                .toList();
    }

    private Map<Long, Map<String, Integer>> tagsBySakeId(List<Long> sakeIds) {
        return sakeTagRepository.findByIdSakeIdIn(sakeIds).stream()
                .collect(Collectors.groupingBy(
                        tag -> tag.getId().getSakeId(),
                        LinkedHashMap::new,
                        Collectors.toMap(tag -> tag.getTag().getName(),
                                tag -> tag.getScore().intValue(),
                                (left, right) -> left, LinkedHashMap::new)));
    }

    private Sake toView(com.example.demo.entity.Sake entity, Map<String, Integer> tags) {
        String breweryName = entity.getBrewery() == null ? "" : entity.getBrewery().getName();
        String breweryPrefecture = entity.getBrewery() == null
                || entity.getBrewery().getPrefecture() == null
                ? "" : entity.getBrewery().getPrefecture();
        return new Sake(entity.getId(), entity.getName(), breweryName, breweryPrefecture,
                entity.getSakeType().getName(),
                entity.getRegion(), entity.getAbv() == null ? 0 : entity.getAbv().doubleValue(),
                entity.getPrice() == null ? 0 : entity.getPrice(), entity.getDescription(),
                entity.getImageUrl(),
                Map.copyOf(tags), entity.getAverageRating(), entity.getReviewCount() == null ? 0 : entity.getReviewCount());
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
