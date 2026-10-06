package com.example.demo.service;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * 短いJSONをテスト内で用意し、紹介情報の整理・入力チェック・DB情報による補完を確認する。
 * assertThatは実際の結果の確認、assertThatThrownByは不正な入力でエラーが起きることの確認。
 */
class SakePageServiceTests {
    private SakePageService load(String json) throws Exception {
        return new SakePageService(JsonMapper.builder().build(), new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
    }
    private Sake sake(long id) {
        return new Sake(id, "元の銘柄名", "元の酒造", "", "", "", 0, 0, "", "", Map.of(), null, 0);
    }
    // 空欄や重複を整理し、購入リンクの先頭を公式ショップにすることを確認する。
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
    // HTTPS以外のリンクと、同じ銘柄IDが複数あるJSONを受け付けないことを確認する。
    @Test void rejectsUnsafeLinksAndDuplicateIds() {
        assertThatThrownBy(() -> load("[{\"id\":1,\"officialUrl\":\"javascript:alert(1)\"}]"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> load("[{\"id\":1},{\"id\":1}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重複");
    }
    // JSONにない銘柄はDBの名前を使い、未登録の紹介文や価格を補って表示しないことを確認する。
    @Test void unknownProductUsesDatabaseIdentityWithoutInventedInformation() throws Exception {
        var page = load("[]").pageFor(sake(99));
        assertThat(page.name()).isEqualTo("元の銘柄名");
        assertThat(page.prices()).isEmpty();
        assertThat(page.introduction()).isEmpty();
    }
    // 負の価格を登録しようとするとエラーになることを確認する。
    @Test void rejectsNegativePrices() {
        assertThatThrownBy(() -> new SakePage.Price("720ml", -1)).isInstanceOf(IllegalArgumentException.class);
    }
    // JSONの銘柄名が空白なら、DBの名前で表示名を補うことを確認する。
    @Test void blankDisplayNameStillProvidesALabelForRelatedLinks() throws Exception {
        assertThat(load("[{\"id\":1,\"name\":\" \"}]").pageFor(sake(1)).name()).isEqualTo("元の銘柄名");
    }
}
