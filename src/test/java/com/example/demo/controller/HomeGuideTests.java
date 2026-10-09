package com.example.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.model.Sake;
import com.example.demo.service.SakeDiscoveryService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HomeGuideTests {
    @Autowired MockMvc mvc;
    @MockitoBean SakeDiscoveryService discovery;

    @Test void anonymousGuideHasAllSixStepsAndNavigation() throws Exception {
        var response = mvc.perform(get("/guide")).andExpect(status().isOk()).andExpect(view().name("guide"));
        for (String id : new String[]{"buy", "choose", "carry", "store", "open", "after"}) {
            response.andExpect(content().string(containsString("id=\"" + id + "\"")))
                    .andExpect(content().string(containsString("href=\"#" + id + "\"")));
        }
        response.andExpect(content().string(containsString("日本酒造組合中央会の保存ガイド")));
    }

    @Test void homeRendersPairAndSelectionEvidence() throws Exception {
        Sake selected = sake(30, "発見した一本");
        var reference = new SakeDiscoveryService.MajorReference("比較の定番", "公式情報と取扱店を確認",
                "https://example.com/product", "https://example.com/shops", "2026-10-09");
        when(discovery.discover()).thenReturn(Optional.of(new SakeDiscoveryService.Discovery(selected,
                new SakeDiscoveryService.MajorMatch(sake(1, "比較の定番"), 1, List.of("甘口"), reference))));
        when(discovery.majorReferences()).thenReturn(List.of(reference));
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("ランダムに選んだ一本")))
                .andExpect(content().string(containsString("似ているメジャー銘柄")))
                .andExpect(content().string(containsString("似ている理由：")))
                .andExpect(content().string(containsString("メジャー銘柄の選定基準と出典")))
                .andExpect(content().string(containsString("href=\"/guide\"")))
                .andExpect(content().string(containsString("href=\"/?draw=")));
    }

    @Test void unavailableComparisonAndEmptyCatalogRemainReadable() throws Exception {
        when(discovery.majorReferences()).thenReturn(List.of());
        when(discovery.discover()).thenReturn(Optional.of(new SakeDiscoveryService.Discovery(sake(30, "一本だけ"), null)));
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("似ているメジャー銘柄は準備中です")));
        when(discovery.discover()).thenReturn(Optional.empty());
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("日本酒の登録を準備しています")));
    }

    private static Sake sake(long id, String name) {
        return new Sake(id, name, "", "", "純米", "", 15, 0, "", "", Map.of("甘口", 3), null, 0);
    }
}
