package com.example.demo.controller;

import com.example.demo.dto.SakePageDto;
import com.example.demo.service.SakeCatalogService;
import com.example.demo.service.SakeInteractionService;
import com.example.demo.service.SakePageService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DBなしで実際の検索フォームを描画し、再送信とページ送りの条件保持を確認する。 */
class SakeSearchRenderingTests {
    private final SakeCatalogService catalog = mock(SakeCatalogService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
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
        mvc = MockMvcBuilders.standaloneSetup(new SakeSearchController(catalog,
                mock(SakePageService.class), mock(SakeInteractionService.class)))
                .setViewResolvers(views).build();
        when(catalog.types()).thenReturn(List.of("純米"));
        when(catalog.tastes()).thenReturn(List.of("甘口", "辛口"));
    }

    @Test void formKeepsKeywordWhenChangingSortAndPrice() throws Exception {
        var oneResult = new SakePageDto(List.of(), 0, 1, 1);
        when(catalog.search("新潟", "", "", null, null, "", "recommended", 0)).thenReturn(oneResult);
        when(catalog.search("新潟", "", "", null, null, "", "priceAsc", 0)).thenReturn(oneResult);
        when(catalog.search("新潟", "", "", 1500, 2999, "", "priceAsc", 0)).thenReturn(oneResult);

        String initial = mvc.perform(get("/search").param("keyword", "新潟"))
                .andExpect(status().isOk()).andExpect(model().attribute("keyword", "新潟"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(initial).contains("<label for=\"search-keyword\">キーワード</label>",
                "value=\"新潟\"", "すべての条件を解除");

        var sortRequest = formValues(initial);
        sortRequest.set("sort", "priceAsc");
        String sorted = mvc.perform(get("/search").params(sortRequest))
                .andExpect(status().isOk()).andExpect(model().attribute("keyword", "新潟"))
                .andExpect(model().attribute("selectedSort", "priceAsc"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var priceRequest = formValues(sorted);
        priceRequest.set("priceRange", "1500-2999");
        mvc.perform(get("/search").params(priceRequest))
                .andExpect(status().isOk()).andExpect(model().attribute("keyword", "新潟"))
                .andExpect(model().attribute("selectedPriceRange", "1500-2999"));
        verify(catalog).search("新潟", "", "", null, null, "", "priceAsc", 0);
        verify(catalog).search("新潟", "", "", 1500, 2999, "", "priceAsc", 0);
    }

    @Test void paginationKeepsEveryConditionAndMarksCurrentPage() throws Exception {
        when(catalog.search("新潟", "山", "純米", 1500, 2999, "辛口", "priceAsc", 1))
                .thenReturn(new SakePageDto(List.of(), 1, 3, 15));

        String html = mvc.perform(get("/search").param("keyword", "新潟").param("name", "山")
                .param("type", "純米").param("taste", "辛口").param("priceRange", "1500-2999")
                .param("sort", "priceAsc").param("page", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String pagination = Pattern.compile("<nav class=\"pagination\".*?</nav>", Pattern.DOTALL)
                .matcher(html).results().findFirst().orElseThrow().group();
        assertThat(pagination).contains("aria-label=\"検索結果のページ\"", "aria-current=\"page\"", "← 前へ", "次へ →");
        var links = Pattern.compile("href=\"([^\"]+)\"").matcher(pagination).results().toList();
        assertThat(links).hasSize(5);
        for (var link : links) {
            String url = java.net.URLDecoder.decode(link.group(1).replace("&amp;", "&"), StandardCharsets.UTF_8);
            var query = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
            assertThat(query).containsEntry("keyword", List.of("新潟"))
                    .containsEntry("name", List.of("山")).containsEntry("type", List.of("純米"))
                    .containsEntry("taste", List.of("辛口")).containsEntry("priceRange", List.of("1500-2999"))
                    .containsEntry("sort", List.of("priceAsc"));
        }
    }

    @Test void keywordIsEscapedAndRecognizedTasteUsesTasteSelection() throws Exception {
        String keyword = "\"><script>alert(1)</script>";
        when(catalog.search(keyword, "", "", null, null, "", "recommended", 0))
                .thenReturn(new SakePageDto(List.of(), 0, 1, 0));
        String html = mvc.perform(get("/search").param("keyword", keyword))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)</script>");

        when(catalog.search("", "", "", null, null, "辛口", "recommended", 0))
                .thenReturn(new SakePageDto(List.of(), 0, 1, 0));
        String tasteHtml = mvc.perform(get("/search").param("keyword", "#辛口"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(formValues(tasteHtml)).containsEntry("keyword", List.of(""))
                .containsEntry("taste", List.of("辛口"));
    }

    /** ブラウザのGET送信と同じく、実際に描画されたinputと選択中のoptionを集める。 */
    private MultiValueMap<String, String> formValues(String html) {
        String form = Pattern.compile("<form class=\"search-form\".*?</form>", Pattern.DOTALL)
                .matcher(html).results().findFirst().orElseThrow().group();
        var values = new LinkedMultiValueMap<String, String>();
        Pattern.compile("<input\\b[^>]*>").matcher(form).results().forEach(input ->
                values.set(attribute(input.group(), "name"), attribute(input.group(), "value")));
        Pattern.compile("<select\\b[^>]*>.*?</select>", Pattern.DOTALL).matcher(form).results().forEach(select -> {
            var options = Pattern.compile("<option\\b[^>]*>").matcher(select.group()).results().map(match -> match.group()).toList();
            String chosen = options.stream().filter(option -> option.contains("selected=\"selected\""))
                    .findFirst().orElse(options.getFirst());
            values.set(attribute(select.group().substring(0, select.group().indexOf('>')), "name"), attribute(chosen, "value"));
        });
        return values;
    }

    private String attribute(String tag, String name) {
        return Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag).results()
                .findFirst().orElseThrow().group(1);
    }
}
