package com.example.demo.controller;

import com.example.demo.entity.Sake;
import com.example.demo.entity.SakeType;
import com.example.demo.model.SakePage;
import com.example.demo.repository.SakeRepository;
import com.example.demo.repository.SakeTagRepository;
import com.example.demo.service.ProductImagePublicationService;
import com.example.demo.service.SakeCatalogService;
import com.example.demo.service.SakeInteractionService;
import com.example.demo.service.SakePageService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実テンプレートが未確認の商品写真URLをHTMLへ出さないことを、DBを使わず確認する。 */
class ProductImageRenderingTests {
    private static final String IMAGE = "https://dassai.com/files/dassai45.jpg";
    private static final String OFFICIAL_PAGE = "https://dassai.com/product/main/45.html";
    private MockMvc mvc;
    private Sake entity;
    private SakeInteractionService interactions;

    @BeforeEach void setup() throws Exception {
        entity = new Sake();
        entity.setId(1L);
        entity.setName("獺祭 純米大吟醸45");
        entity.setSakeType(new SakeType(1L, "純米大吟醸"));
        entity.setImageUrl(IMAGE);
        var repository = mock(SakeRepository.class);
        var tags = mock(SakeTagRepository.class);
        when(tags.findByIdSakeIdIn(any())).thenReturn(List.of());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(repository.search(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));
        var catalog = new SakeCatalogService(repository, tags, new ProductImagePublicationService(
                new ObjectMapper(), new ClassPathResource("data/product-image-publication.json")));
        var pages = mock(SakePageService.class);
        when(pages.pageFor(any())).thenReturn(new SakePage(1, entity.getName(), "", "",
                null, null, null, null, null, null, OFFICIAL_PAGE, null, null));
        interactions = mock(SakeInteractionService.class);
        when(interactions.publicReviews(1L, "newest", 0)).thenReturn(
                new SakeInteractionService.ReviewPage(List.of(), 0, 0, 0, 1, "newest"));

        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SpringSecurityDialect());
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mvc = MockMvcBuilders.standaloneSetup(new SakeSearchController(catalog, pages, interactions))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setViewResolvers(views).build();
    }

    @Test void searchUsesExistingPhotoFallbackWithoutEmittingExternalImageUrl() throws Exception {
        String html = mvc.perform(get("/search")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("商品写真準備中", "獺祭 純米大吟醸45", "href=\"/images/site-icon.png\"", "src=\"/images/icon2.png\"")
                .doesNotContain(IMAGE);
        assertThat(entity.getImageUrl()).isEqualTo(IMAGE);
    }

    @Test void detailKeepsOfficialPageLinkAndSiteIconWhileOmittingUnapprovedPhoto() throws Exception {
        String html = mvc.perform(get("/sake/1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(OFFICIAL_PAGE, "href=\"/images/site-icon.png\"")
                .doesNotContain(IMAGE, "class=\"sake-product-photo\"");
        assertThat(entity.getImageUrl()).isEqualTo(IMAGE);
    }

    @Test void productPhotoPolicyDoesNotHidePublicReviewProfileImages() throws Exception {
        var review = new SakeInteractionService.PublicReview(7L, "公開レビュー投稿者", true,
                50, 50, 100, 4, "参考になりました", java.time.LocalDateTime.of(2026, 10, 20, 12, 0));
        when(interactions.publicReviews(1L, "newest", 0)).thenReturn(
                new SakeInteractionService.ReviewPage(List.of(review), 1, 4, 0, 1, "newest"));
        String html = mvc.perform(get("/sake/1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("src=\"/sake/reviewers/7/profile-image\"", "公開レビュー投稿者")
                .doesNotContain(IMAGE);
    }
}
