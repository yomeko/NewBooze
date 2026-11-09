package com.example.demo.service;

import com.example.demo.model.SakePage;
import com.example.demo.repository.BreweryRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 特集ページに掲載する、登録済みの料理例と酒蔵情報を取り出す。 */
@Service
public class SakeCollectionService {
    private final SakeCatalogService catalog;
    private final SakePageService pages;
    private final BreweryRepository breweries;

    public SakeCollectionService(SakeCatalogService catalog, SakePageService pages, BreweryRepository breweries) {
        this.catalog = catalog;
        this.pages = pages;
        this.breweries = breweries;
    }

    @Transactional(readOnly = true)
    public List<SakePage> foodSuggestions() {
        // 詳細画面と同じ紹介データを使い、料理が未登録の銘柄には例を補わない。
        return pages.pagesForCatalog(catalog.all()).stream().filter(page -> !page.food().isEmpty()).toList();
    }

    public record BreweryLink(String name, String mapUrl) {}

    @Transactional(readOnly = true)
    public Map<String, List<BreweryLink>> breweriesByPrefecture() {
        Map<String, List<BreweryLink>> groups = new LinkedHashMap<>();
        List<BreweryLink> withoutPrefecture = new ArrayList<>();
        for (var brewery : breweries.findAll(Sort.by("prefecture", "name", "id"))) {
            String prefecture = SakePage.clean(brewery.getPrefecture());
            String name = brewery.getName();
            // 座標がないため酒蔵名と都道府県を地図検索へ渡す。ページに地図は埋め込まない。
            String query = URLEncoder.encode((prefecture + " " + name).strip(), StandardCharsets.UTF_8);
            var link = new BreweryLink(name, "https://www.google.com/maps/search/?api=1&query=" + query);
            if (prefecture.isEmpty()) {
                withoutPrefecture.add(link);
            } else {
                groups.computeIfAbsent(prefecture, unused -> new ArrayList<>()).add(link);
            }
        }
        if (!withoutPrefecture.isEmpty()) {
            groups.computeIfAbsent("所在地未登録", unused -> new ArrayList<>()).addAll(withoutPrefecture);
        }
        return groups;
    }
}
