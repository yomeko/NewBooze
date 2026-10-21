package com.example.demo.controller;

import com.example.demo.config.PublicationInformation;
import com.example.demo.service.SakeDiscoveryService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicationPageRenderingTests {
    private String render(String path, PublicationInformation info) throws Exception {
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
        return MockMvcBuilders.standaloneSetup(new HomeController(mock(SakeDiscoveryService.class), info))
                .setViewResolvers(views).build().perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void unsetContactDoesNotCreateAnAddressOrSubmissionForm() throws Exception {
        assertThat(render("/contact", new PublicationInformation("", "")))
                .contains("問い合わせ窓口は準備中", "href=\"/mypage/account\"", "href=\"/forgot-password\"")
                .doesNotContain("mailto:", "method=\"post\"");
    }

    @Test void configuredContactProducesOnlyTheIntendedMailtoAddress() throws Exception {
        assertThat(render("/contact", new PublicationInformation("研究担当", "contact+research@example.com")))
                .contains("href=\"mailto:contact+research@example.com\"", ">contact+research@example.com</a>")
                .doesNotContain("問い合わせ窓口は準備中");
    }

    @Test void operatorSettingIsEscapedInAboutAndPrivacyPages() throws Exception {
        var info = new PublicationInformation("<script>alert(1)</script>", "");
        for (String path : new String[]{"/about", "/privacy"}) {
            assertThat(render(path, info)).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                    .doesNotContain("<script>alert(1)</script>");
        }
    }
}
