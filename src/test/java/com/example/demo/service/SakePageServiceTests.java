package com.example.demo.service;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SakePageServiceTests {
    private SakePageService load(String json) throws Exception {
        return new SakePageService(JsonMapper.builder().build(), new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
    }
    private Sake sake(long id) {
        return new Sake(id, "元の銘柄名", "元の酒造", "", "", "", 0, 0, "", "", Map.of());
    }
    @Test void omitsBlankFieldsAndPrioritizesOfficialShop() throws Exception {
        var service = load("""
          [{"id":1,"name":"銘柄","taste":["", " 甘み ", null],"prices":[{"volume":"","yen":null}],
          "similarIds":[1,2,2,null,-1],"purchaseLinks":[
          {"label":"店","url":"https://shop.example/item","official":false},
          {"label":"公式","url":"https://official.example/item","official":true}]}]
          """);
        var page = service.pageFor(sake(1));
        assertThat(page.taste()).containsExactly("甘み");
        assertThat(page.prices()).isEmpty();
        assertThat(page.aroma()).isEmpty();
        assertThat(page.similarIds()).containsExactly(2L);
        assertThat(page.purchaseLinks().getFirst().official()).isTrue();
        assertThat(page.brewery()).isEmpty();
    }
    @Test void rejectsUnsafeLinksAndDuplicateIds() {
        assertThatThrownBy(() -> load("[{\"id\":1,\"officialUrl\":\"javascript:alert(1)\"}]"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> load("[{\"id\":1},{\"id\":1}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重複");
    }
    @Test void unknownProductUsesDatabaseIdentityWithoutInventedInformation() throws Exception {
        var page = load("[]").pageFor(sake(99));
        assertThat(page.name()).isEqualTo("元の銘柄名");
        assertThat(page.prices()).isEmpty();
        assertThat(page.introduction()).isEmpty();
    }
    @Test void rejectsNegativePrices() {
        assertThatThrownBy(() -> new SakePage.Price("720ml", -1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void blankDisplayNameStillProvidesALabelForRelatedLinks() throws Exception {
        assertThat(load("[{\"id\":1,\"name\":\" \"}]").pageFor(sake(1)).name()).isEqualTo("元の銘柄名");
    }
}
