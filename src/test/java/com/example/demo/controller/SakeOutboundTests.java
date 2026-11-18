package com.example.demo.controller;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.SakeCatalogService;
import com.example.demo.service.SakePageService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SakeOutboundTests {
    private final SakeCatalogService catalog = mock(SakeCatalogService.class);
    private final SakePageService pages = mock(SakePageService.class);
    private final AnalyticsService analytics = mock(AnalyticsService.class);
    private final Sake sake = new Sake(42, "テスト", "", "", "純米", "", 15, 0, "", "", Map.of(), null, 0);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new SakeOutboundController(catalog, pages, analytics)).build();
        when(catalog.findById(42)).thenReturn(Optional.of(sake));
    }

    @Test void purchaseWithoutOfficialShopFallsBackToOfficialPageAndIgnoresUnofficialShop() throws Exception {
        when(pages.pageFor(sake)).thenReturn(page("https://brewery.example/", List.of(
                new SakePage.PurchaseLink("販売店", "https://shop.example/", false))));
        mvc.perform(get("/sake/42/outbound/purchase")).andExpect(status().isFound())
                .andExpect(redirectedUrl("https://brewery.example/"));
        verify(analytics).recordOfficialClick(42);
    }

    @Test void shopWithoutOfficialPageIsValidAndOfficialButtonReturns404() throws Exception {
        when(pages.pageFor(sake)).thenReturn(page("", List.of(
                new SakePage.PurchaseLink("公式", "https://official-shop.example/", true))));
        mvc.perform(get("/sake/42/outbound/purchase")).andExpect(status().isFound())
                .andExpect(redirectedUrl("https://official-shop.example/"));
        mvc.perform(get("/sake/42/outbound/official")).andExpect(status().isNotFound());
        verify(analytics, times(1)).recordOfficialClick(42);
    }

    @Test void databaseAndCommitFailuresStillAllowOfficialNavigation() throws Exception {
        when(pages.pageFor(sake)).thenReturn(page("https://brewery.example/", List.of()));
        for (var failure : new RuntimeException[]{new DataAccessResourceFailureException("offline"),
                new TransactionSystemException("commit failed")}) {
            doThrow(failure).when(analytics).recordOfficialClick(42);
            mvc.perform(get("/sake/42/outbound/official")).andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://brewery.example/"));
        }
    }

    private SakePage page(String official, List<SakePage.PurchaseLink> links) {
        return new SakePage(42, "テスト", "", "", null, null, null, null, null, null, official, null, links);
    }
}
