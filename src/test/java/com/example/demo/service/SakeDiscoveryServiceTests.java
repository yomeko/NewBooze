package com.example.demo.service;

import com.example.demo.model.Sake;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SakeDiscoveryServiceTests {
    private final SakeCatalogService catalog = mock(SakeCatalogService.class);
    private final SakeDiscoveryService service;

    SakeDiscoveryServiceTests() throws Exception {
        service = new SakeDiscoveryService(catalog, new ObjectMapper(), new ClassPathResource("data/major-sake.json"));
    }

    @Test void selectsNearestMajorByTagRatiosAndExcludesSelfAndNonMajor() {
        Sake selected = sake(30, "発見した一本", Map.of("甘口", 4, "フルーティー", 5));
        Sake closest = sake(1, "獺祭 純米大吟醸45", selected.tagScores());
        Sake other = sake(8, "八海山 特別本醸造", Map.of("辛口", 5));
        Sake similar = sake(7, "出羽桜 桜花吟醸", Map.of("フルーティー", 5, "軽快", 4));
        var match = service.similarMajor(selected, List.of(selected, other, similar, closest)).orElseThrow();
        assertThat(match.sake().id()).isEqualTo(1);
        assertThat(match.matchingTags()).containsExactly("フルーティー", "甘口");
        assertThat(match.score()).isCloseTo(1, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(service.similarMajor(closest, List.of(closest, selected, similar)).orElseThrow().sake().id()).isEqualTo(7);
    }

    @Test void tiesUseIdOrderRegardlessOfCatalogOrder() {
        Sake selected = sake(30, "発見した一本", Map.of("軽快", 3));
        var match = service.similarMajor(selected, List.of(
                sake(8, "八海山 特別本醸造", Map.of("軽快", 5)),
                sake(7, "出羽桜 桜花吟醸", Map.of("軽快", 4)))).orElseThrow();
        assertThat(match.sake().id()).isEqualTo(7);
    }

    @Test void missingZeroNegativeAndUnsharedTagsNeverProduceFalseMatch() {
        var candidates = List.of(sake(1, "獺祭 純米大吟醸45", Map.of("甘口", 4)));
        for (var tags : List.<Map<String, Integer>>of(Map.of(), Map.of("甘口", 0),
                Map.of("甘口", -3), Map.of("辛口", 5))) {
            assertThat(service.similarMajor(sake(30, "発見した一本", tags), candidates)).isEmpty();
        }
        assertThat(service.similarMajor(sake(30, "発見した一本", Map.of("甘口", 4)),
                List.of(sake(40, "未指定の銘柄", Map.of("甘口", 4))))).isEmpty();
    }

    @Test void emptyCatalogAndOneSakeAreHandled() {
        when(catalog.all()).thenReturn(List.of());
        assertThat(service.discover()).isEmpty();
        Sake single = sake(1, "獺祭 純米大吟醸45", Map.of("甘口", 4));
        when(catalog.all()).thenReturn(List.of(single));
        var discovery = service.discover().orElseThrow();
        assertThat(discovery.sake()).isEqualTo(single);
        assertThat(discovery.similar()).isNull();
    }

    @Test void discoverySelectsExactlyOneExistingSake() {
        var all = List.of(sake(19, "十九", Map.of()), sake(42, "四十二", Map.of()), sake(95, "九十五", Map.of()));
        when(catalog.all()).thenReturn(all);
        assertThat(service.discover().orElseThrow().sake()).isIn(all);
        verify(catalog).all();
    }

    private static Sake sake(long id, String name, Map<String, Integer> tags) {
        return new Sake(id, name, "", "", "純米", "", 15, 0, "", "", tags, null, 0);
    }
}
