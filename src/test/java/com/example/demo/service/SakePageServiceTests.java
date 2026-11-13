package com.example.demo.service;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @Test void preservesOfficialAbvRangeWithoutGuessingASingleValue() throws Exception {
        var page = load("""
                [{"id":1,"name":" ","abvText":" 15度以上16度未満 ",
                  "introduction":"既存の紹介文","prices":[{"volume":"720ml","yen":1800}]}]
                """).pageFor(sake(1));
        assertThat(page.name()).isEqualTo("元の銘柄名");
        assertThat(page.abvText()).isEqualTo("15度以上16度未満");
        assertThat(page.abvDisplay(0)).isEqualTo("15度以上16度未満");
        assertThat(page.abvDisplay(15.5)).isEqualTo("15.5%");
        assertThat(page.introduction()).isEqualTo("既存の紹介文");
        assertThat(page.prices()).containsExactly(new SakePage.Price("720ml", 1800));
        assertThat(load("[{\"id\":1}]").pageFor(sake(1)).abvDisplay(0)).isEqualTo("未確認");
        assertThatThrownBy(() -> load("[{\"id\":1,\"abvText\":\"" + "長".repeat(121) + "\"}]"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void searchAbvLabelsUseOneDatabaseQueryAndKeepFallbacks() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = new SakePageService(JsonMapper.builder().build(), new ByteArrayResource("""
                [{"id":1,"abvText":"同梱の古い表記"},{"id":2,"abvText":"14〜15度"}]
                """.getBytes(StandardCharsets.UTF_8)), jdbc);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            var row = mock(java.sql.ResultSet.class);
            when(row.getLong("sake_id")).thenReturn(1L);
            when(row.getString("detail_json")).thenReturn("{\"id\":1,\"abvText\":\"15度〜16度\"}");
            return List.of(rowMapper.mapRow(row, 0));
        });
        var numeric = new Sake(3, "単一度数", "", "", "", "", 17, 0, "", "", Map.of(), null, 0);
        assertThat(service.abvLabelsFor(List.of(sake(1), sake(2), numeric, sake(4))))
                .containsEntry(1L, "15度〜16度").containsEntry(2L, "14〜15度")
                .containsEntry(3L, "17.0%").containsEntry(4L, "未確認");
        verify(jdbc).query(eq("SELECT sake_id, detail_json FROM sake_page_details WHERE sake_id IN (?,?,?)"),
                any(RowMapper.class), eq(1L), eq(2L), eq(4L));
        verifyNoMoreInteractions(jdbc);
        assertThat(service.abvLabelsFor(List.of())).isEmpty();
        verifyNoMoreInteractions(jdbc);
    }

    @Test void searchIgnoresInvalidDetailsAndReadsOnlyTheOfficialText() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = new SakePageService(JsonMapper.builder().build(), new ByteArrayResource("""
                [{"id":1,"abvText":"DBより古い範囲表記"}]
                """.getBytes(StandardCharsets.UTF_8)), jdbc);
        var json = List.of("{broken", "{\"abvText\":15}",
                "{\"abvText\":\"" + "長".repeat(121) + "\"}",
                "{\"officialUrl\":\"javascript:unsafe()\",\"unknownField\":true,\"abvText\":\" 16〜17度 \"}",
                "{\"id\":999,\"abvText\":\"別のIDの度数\"}",
                "{\"name\":\"別の銘柄名\",\"abvText\":\"別の商品の度数\"}");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            var values = new java.util.ArrayList<>();
            for (int index = 0; index < json.size(); index++) {
                var row = mock(java.sql.ResultSet.class);
                when(row.getLong("sake_id")).thenReturn((long) index + 1);
                when(row.getString("detail_json")).thenReturn(json.get(index));
                values.add(rowMapper.mapRow(row, index));
            }
            return values;
        });
        assertThat(service.abvLabelsFor(List.of(sake(1), sake(2), sake(3), sake(4), sake(5), sake(6))))
                .containsEntry(1L, "未確認").containsEntry(2L, "未確認")
                .containsEntry(3L, "未確認").containsEntry(4L, "16〜17度")
                .containsEntry(5L, "未確認").containsEntry(6L, "未確認");
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(1L), eq(2L), eq(3L), eq(4L), eq(5L), eq(6L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void numericSearchResultsDoNotReadDetailJson() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = new SakePageService(JsonMapper.builder().build(),
                new ByteArrayResource("[]".getBytes(StandardCharsets.UTF_8)), jdbc);
        var numeric = new Sake(1, "数値度数", "", "", "", "", 15.5, 0, "", "", Map.of(), null, 0);
        assertThat(service.abvLabelsFor(List.of(numeric))).containsEntry(1L, "15.5%");
        verifyNoInteractions(jdbc);
    }

    @Test void priceLabelsPrefer720mlAndRetainAlternateCapacityAndSourceOrder() {
        var prices = List.of(new SakePage.Price("1800ml", 6600), new SakePage.Price("500ml", 2400),
                new SakePage.Price("720 ml", 2750), new SakePage.Price("720ml", 2200));
        assertThat(SakePage.formatPrice(0, prices)).isEqualTo("720 ml 2,750円（税込）");
        assertThat(SakePage.formatPrice(2400, prices)).isEqualTo("500ml 2,400円（税込）");
        assertThat(SakePage.formatPrice(0, prices.subList(0, 2))).isEqualTo("1800ml 6,600円（税込）");
        var page = new SakePage(1, "", "", "", null, null, prices, null, null, null, null, null, null);
        assertThat(page.orderedPrices()).containsExactly(prices.get(2), prices.get(3), prices.get(0), prices.get(1));
        assertThat(page.prices()).containsExactlyElementsOf(prices);
    }

    @Test void priceLabelsNeverGuessCapacityOrInventZeroPrices() {
        assertThat(SakePage.formatPrice(2180, List.of(new SakePage.Price("720ml", 2475))))
                .isEqualTo("参考価格：2,180円（対象容量：未確認）");
        assertThat(SakePage.formatPrice(2500, List.of(new SakePage.Price("", 2500))))
                .isEqualTo("対象容量：未確認 2,500円（税込）");
        assertThat(SakePage.formatPrice(0, List.of(new SakePage.Price("720ml", null))))
                .isEqualTo("価格：未確認");
        assertThat(SakePage.formatPrice(0, List.of())).isEqualTo("価格：未確認");
    }

    @Test void displayingCapacityDoesNotAddDerivedFieldsToSavedPriceJson() {
        var mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(new SakePage.Price("500ml", 2400));
        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree("{\"volume\":\"500ml\",\"yen\":2400}"));
    }

    @Test void searchGetsAbvAndCapacityPricesInASingleQueryAndGuardsInvalidPrices() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = new SakePageService(JsonMapper.builder().build(), new ByteArrayResource("[]".getBytes(StandardCharsets.UTF_8)), jdbc);
        var json = List.of(
                "{\"id\":1,\"name\":\"元の銘柄名\",\"abvText\":\"15度〜16度\",\"prices\":[{\"volume\":\"500ml\",\"yen\":2400}]}",
                "{\"prices\":[{\"volume\":\"1800ml\",\"yen\":6600},{\"volume\":\"720ml\",\"yen\":2750}]}",
                "{\"prices\":[{\"volume\":\"500ml\",\"yen\":0},{\"volume\":\"720ml\",\"yen\":\"2000\"},{\"volume\":500,\"yen\":2400}]}",
                "{\"id\":99,\"prices\":[{\"volume\":\"720ml\",\"yen\":2000}]}"
        );
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            var values = new java.util.ArrayList<>();
            for (int index = 0; index < json.size(); index++) {
                var row = mock(java.sql.ResultSet.class);
                when(row.getLong("sake_id")).thenReturn((long) index + 1);
                when(row.getString("detail_json")).thenReturn(json.get(index));
                values.add(rowMapper.mapRow(row, index));
            }
            return values;
        });
        var labels = service.specificationsFor(List.of(sake(1), sake(2), sake(3), sake(4)));
        assertThat(labels.get(1L)).isEqualTo(new SakePageService.SpecificationLabels("15度〜16度", "500ml 2,400円（税込）"));
        assertThat(labels.get(2L).price()).isEqualTo("720ml 2,750円（税込）");
        assertThat(labels.get(3L).price()).isEqualTo("価格：未確認");
        assertThat(labels.get(4L).price()).isEqualTo("価格：未確認");
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(1L), eq(2L), eq(3L), eq(4L));
        verifyNoMoreInteractions(jdbc);
        assertThat(service.specificationsFor(List.of())).isEmpty();
        verifyNoMoreInteractions(jdbc);
    }

}
