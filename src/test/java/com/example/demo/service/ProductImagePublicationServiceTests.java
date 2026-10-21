package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class ProductImagePublicationServiceTests {
    private static final String NAME = "確認用銘柄";
    private static final String IMAGE = "https://images.example/product/one.png";
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> approval(String url) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("sakeId", 1);
        entry.put("sakeName", NAME);
        entry.put("imageUrl", url);
        entry.put("approved", true);
        entry.put("rightsHolder", "写真権利者");
        entry.put("permissionSource", "https://rights.example/permissions/product-one");
        entry.put("permissionCheckedOn", "2020-10-21");
        entry.put("usageConditions", "このサイトでの掲載と画像配信方法が許諾範囲内であることを確認済み。");
        return entry;
    }

    private ProductImagePublicationService load(List<?> entries) throws Exception {
        return new ProductImagePublicationService(mapper,
                new ByteArrayResource(mapper.writeValueAsBytes(entries)));
    }

    @Test void bundledCandidatesRemainUnapproved() throws Exception {
        var resource = new ClassPathResource("data/product-image-publication.json");
        var service = new ProductImagePublicationService(mapper, resource);
        ProductImagePublicationService.PhotoApproval[] entries;
        try (var input = resource.getInputStream()) {
            entries = mapper.readValue(input, ProductImagePublicationService.PhotoApproval[].class);
        }
        assertThat(entries).hasSize(7);
        for (var entry : entries) {
            assertThat(entry.approved()).isFalse();
            assertThat(service.displayUrl(entry.sakeId(), entry.sakeName(), entry.imageUrl())).isNull();
        }
    }

    @Test void approvalMatchesOnlyExactProductIdentityAndUrl() throws Exception {
        var service = load(List.of(approval(IMAGE)));
        assertThat(service.displayUrl(1, NAME, IMAGE)).isEqualTo(IMAGE);
        assertThat(service.displayUrl(2, NAME, IMAGE)).isNull();
        assertThat(service.displayUrl(1, "別銘柄", IMAGE)).isNull();
        for (String other : List.of(IMAGE + "?version=2", IMAGE + "#fragment",
                "https://images.example/product/two.png", "https://other.example/product/one.png",
                " " + IMAGE, IMAGE + " ")) {
            assertThat(service.displayUrl(1, NAME, other)).isNull();
        }
    }

    @Test void localAndUnknownImagesAlsoRequireIndividualApproval() throws Exception {
        var service = load(List.of());
        assertThat(service.displayUrl(1, NAME, null)).isNull();
        for (String url : List.of("", " ", IMAGE, "/images/sake/own-photo.jpg",
                "/sake/images/00000000-0000-0000-0000-000000000001.png")) {
            assertThat(service.displayUrl(1, NAME, url)).isNull();
        }
        String local = "/images/sake/own-photo.jpg";
        assertThat(load(List.of(approval(local))).displayUrl(1, NAME, local)).isEqualTo(local);
    }

    @Test void missingPermissionMetadataNeverActivatesAnApproval() throws Exception {
        for (String field : List.of("sakeId", "sakeName", "imageUrl", "rightsHolder", "permissionSource",
                "permissionCheckedOn", "usageConditions", "approved")) {
            var entry = approval(IMAGE);
            entry.remove(field);
            assertThat(load(List.of(entry)).displayUrl(1, NAME, IMAGE)).as(field).isNull();
        }
        for (String field : List.of("sakeName", "rightsHolder", "permissionSource",
                "permissionCheckedOn", "usageConditions")) {
            var entry = approval(IMAGE);
            entry.put(field, " ");
            assertThat(load(List.of(entry)).displayUrl(1, NAME, IMAGE)).as(field).isNull();
        }
        for (String field : List.of("sakeId", "approved")) {
            var entry = approval(IMAGE);
            entry.put(field, null);
            assertThat(load(List.of(entry)).displayUrl(1, NAME, IMAGE)).as(field + "=null").isNull();
        }
    }

    @Test void invalidPermissionDateOrSourceNeverActivatesAnApproval() throws Exception {
        for (String date : List.of("2026-02-31", "2026/10/21", "未確認",
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).plusDays(1).toString())) {
            var entry = approval(IMAGE);
            entry.put("permissionCheckedOn", date);
            assertThat(load(List.of(entry)).displayUrl(1, NAME, IMAGE)).isNull();
        }
        for (String source : List.of("公式出典のみ", "http://rights.example/terms",
                "javascript:alert(1)", "https://user:password@rights.example/terms")) {
            var entry = approval(IMAGE);
            entry.put("permissionSource", source);
            assertThat(load(List.of(entry)).displayUrl(1, NAME, IMAGE)).isNull();
        }
    }

    @Test void permissionSourceMayLinkToTheRelevantSectionOfAnHttpsPolicy() throws Exception {
        var entry = approval(IMAGE);
        entry.put("permissionSource", "https://rights.example/terms#product-images");
        assertThat(load(List.of(entry)).displayUrl(1, NAME, IMAGE)).isEqualTo(IMAGE);
    }

    @Test void unsafeImageUrlsAreDeniedEvenWithApprovalMetadata() throws Exception {
        for (String url : List.of("http://images.example/one.png", "javascript:alert(1)",
                "data:image/png;base64,AAAA", "//images.example/one.png", "/images/sake/../site-icon.png",
                "/images/sake/%2e%2e/site-icon.png", "https://user:password@images.example/one.png",
                "https://images.example/one.png#fragment", "https://images.example/one png")) {
            assertThat(load(List.of(approval(url))).displayUrl(1, NAME, url)).as(url).isNull();
        }
    }

    @Test void anExplicitUnapprovedEntryCannotPublishDespiteItsOtherMetadata() throws Exception {
        var entry = approval(IMAGE);
        entry.put("approved", false);
        assertThat(load(List.of(entry)).displayUrl(1, NAME, IMAGE)).isNull();
    }

    @Test void duplicateOrUnreadableLedgerCannotSilentlyEnableImages() {
        assertThatThrownBy(() -> load(List.of(approval(IMAGE), approval(IMAGE))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重複");
        assertThatThrownBy(() -> new ProductImagePublicationService(mapper,
                new ByteArrayResource("not-json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(RuntimeException.class);
    }
}
