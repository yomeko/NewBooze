package com.example.demo.service;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class SakePageService {
    private final Map<Long, SakePage> pages;

    public SakePageService(ObjectMapper mapper,
            @Value("${app.sake-pages:classpath:data/sake-pages.json}") Resource source) throws IOException {
        Map<Long, SakePage> loaded = new LinkedHashMap<>();
        try (var input = source.getInputStream()) {
            for (SakePage page : mapper.readValue(input, SakePage[].class)) {
                if (loaded.putIfAbsent(page.id(), page) != null)
                    throw new IllegalArgumentException("日本酒IDが重複しています: " + page.id());
            }
        }
        pages = Map.copyOf(loaded);
    }

    public SakePage pageFor(Sake sake) {
        // A newly registered DB product works even before editorial data is supplied.
        SakePage page = pages.getOrDefault(sake.id(), new SakePage(sake.id(), sake.name(), "",
                sake.breweryName(), null, null, null, null, null, null, null, null, null));
        if (!page.name().isEmpty()) return page;
        return new SakePage(page.id(), sake.name(), page.introduction(), page.brewery(),
                page.taste(), page.aroma(), page.prices(), page.drinking(), page.food(),
                page.recommendedFor(), page.officialUrl(), page.similarIds(), page.purchaseLinks());
    }
}
