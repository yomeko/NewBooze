package com.example.demo.controller;

import com.example.demo.entity.SakeType;
import com.example.demo.entity.Tag;
import com.example.demo.repository.*;
import com.example.demo.service.SakeImageService;
import com.example.demo.service.SakePageService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 実際のThymeleafをDBなしで描画し、選択順の送信欄と再表示のチェック状態を確認する。 */
class AdminTagRenderingTests {
    private final SakeRepository sake = mock(SakeRepository.class);
    private final SakeTypeRepository types = mock(SakeTypeRepository.class);
    private final TagRepository tags = mock(TagRepository.class);
    private final SakeTagRepository sakeTags = mock(SakeTagRepository.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var controller = new AdminController(mock(UserRepository.class), sake, types, mock(SakeImageService.class),
                mock(SakePageService.class), mock(BreweryRepository.class), tags, sakeTags);
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
        mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(views).build();
        when(types.findById(1L)).thenReturn(Optional.of(new SakeType(1L, "純米")));
        when(types.findAll(Sort.by("id"))).thenReturn(List.of(new SakeType(1L, "純米")));
        when(tags.findAll(Sort.by("category", "id"))).thenReturn(List.of(
                new Tag(1L, "フルーティー", "味わい"), new Tag(2L, "旨口", "味わい"),
                new Tag(3L, "辛口", "味わい"), new Tag(4L, "軽快", "味わい"),
                new Tag(5L, "芳醇", "味わい"), new Tag(6L, "酸味", "味わい"), new Tag(7L, "甘口", "味わい")));
    }

    @Test void emptyFormRendersUnselectedChoicesAndWeightScript() throws Exception {
        String html = mvc.perform(get("/admin/sake/new"))
                .andExpect(status().isOk()).andExpect(view().name("admin/sake-new"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(hiddenTagIds(html)).isEmpty();
        assertThat(checkedTagIds(html)).isEmpty();
        assertThat(inputs(html).stream().filter(input -> input.contains("data-tag-choice")))
                .hasSize(7).allSatisfy(input -> assertThat(input).doesNotContain("name=\"tagIds\""));
        assertThat(html).contains("タグは未選択です。", "src=\"/js/admin-sake-tags.js\"", "href=\"/css/admin.css\"")
                .doesNotContain("th:each", "th:checked");
    }

    @Test void validationErrorRendersHiddenIdsInSelectionOrderAndChecksMatchingChoices() throws Exception {
        when(tags.findAllById(List.of(3L, 4L, 1L))).thenReturn(List.of(
                new Tag(1L, "フルーティー", "味わい"), new Tag(3L, "辛口", "味わい"), new Tag(4L, "軽快", "味わい")));

        String html = mvc.perform(post("/admin/sake/new").param("name", "重み付けテスト")
                .param("sakeTypeId", "1").param("officialUrl", "http://invalid.example")
                .param("tagIds", "3", "4", "1"))
                .andExpect(status().isOk()).andExpect(view().name("admin/sake-new"))
                .andExpect(model().attributeHasFieldErrors("sakeForm", "officialUrl"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(hiddenTagIds(html)).containsExactly("3", "4", "1");
        assertThat(checkedTagIds(html)).containsExactly("1", "3", "4");
        assertThat(html).contains("https://で始まる有効なURLを入力してください", "value=\"重み付けテスト\"");
        verifyNoInteractions(sake, sakeTags);
    }

    @Test void unknownTagErrorRendersSelectionOrderAndOnlyChecksExistingChoices() throws Exception {
        when(tags.findAllById(List.of(3L, 999L, 1L))).thenReturn(List.of(
                new Tag(1L, "フルーティー", "味わい"), new Tag(3L, "辛口", "味わい")));

        String html = mvc.perform(post("/admin/sake/new").param("name", "重み付けテスト")
                .param("sakeTypeId", "1").param("tagIds", "3", "999", "1"))
                .andExpect(status().isOk()).andExpect(view().name("admin/sake-new"))
                .andExpect(model().attributeHasFieldErrors("sakeForm", "tagIds"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(hiddenTagIds(html)).containsExactly("3", "999", "1");
        assertThat(checkedTagIds(html)).containsExactly("1", "3");
        assertThat(html).contains("一覧にあるタグを選択してください");
        verifyNoInteractions(sake, sakeTags);
    }

    private List<String> hiddenTagIds(String html) {
        return inputs(html).stream().filter(input -> input.contains("type=\"hidden\"") && input.contains("name=\"tagIds\""))
                .map(this::value).toList();
    }

    private List<String> checkedTagIds(String html) {
        return inputs(html).stream().filter(input -> input.contains("data-tag-choice") && input.contains("checked=\"checked\""))
                .map(this::value).toList();
    }

    private List<String> inputs(String html) {
        return Pattern.compile("<input\\b[^>]*>").matcher(html).results().map(match -> match.group()).toList();
    }

    private String value(String input) {
        return Pattern.compile("\\bvalue=\"([^\"]*)\"").matcher(input).results().findFirst().orElseThrow().group(1);
    }
}
