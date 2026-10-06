package com.example.demo.service;

import com.example.demo.repository.SakeRepository;
import com.example.demo.repository.SakeTagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.mockito.Mockito.*;

/**
 * DB操作を代役（mock）に置き換え、注目銘柄の抽選と検索条件の受け渡しを確認するテスト。
 * whenで代役の返答を決め、verifyでサービスが想定したDB操作を呼んだか確認する。
 */
class SakeCatalogServiceTests {
    private final SakeRepository repository = mock(SakeRepository.class);
    private final SakeCatalogService service = new SakeCatalogService(
            repository, mock(SakeTagRepository.class));

    // 銘柄が0件なら空の一覧を返し、不要な詳細取得をしないことを確認する。
    @Test
    void featuredHandlesEmptyCatalogWithoutLoadingDetails() {
        when(repository.findAllIds()).thenReturn(java.util.List.of());
        org.assertj.core.api.Assertions.assertThat(service.featured()).isEmpty();
        verify(repository, never()).findAllById(any());
    }

    // 登録されている銘柄から、重複せず最大4件を抽選することを確認する。
    @Test
    void featuredSelectsAtMostFourDistinctExistingIds() {
        var ids = java.util.List.of(2L, 7L, 19L, 28L, 45L, 90L, 102L);
        when(repository.findAllIds()).thenReturn(ids);
        when(repository.findAllById(any())).thenAnswer(invocation -> {
            java.util.List<Long> selected = invocation.getArgument(0);
            org.assertj.core.api.Assertions.assertThat(selected)
                    .hasSize(4).doesNotHaveDuplicates().isSubsetOf(ids);
            return java.util.List.of();
        });
        service.featured();
        verify(repository).findAllById(any());
    }

    // 銘柄が4件未満なら、登録済みの全銘柄が抽選対象に残ることを確認する。
    @Test
    void featuredIncludesAllIdsWhenFewerThanFourExist() {
        when(repository.findAllIds()).thenReturn(java.util.List.of(7L, 19L));
        when(repository.findAllById(any())).thenAnswer(invocation -> {
            java.util.List<Long> selected = invocation.getArgument(0);
            org.assertj.core.api.Assertions.assertThat(selected).containsExactlyInAnyOrder(7L, 19L);
            return java.util.List.of();
        });
        service.featured();
        verify(repository).findAllById(any());
    }

    // 入力の前後の空白を取り除き、銘柄名とほかの検索条件を一緒にDBへ渡すことを確認する。
    @Test
    void combinesTrimmedNameWithOtherFilters() {
        var pageable = PageRequest.of(1, 6,
                Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id")));
        when(repository.search("果実", "獺祭", "純米", 1500, 2999, "甘口", pageable))
                .thenReturn(Page.empty(pageable));

        service.search(" 果実 ", " 獺祭 ", "純米", 1500, 2999, "甘口", "priceAsc", 1);

        verify(repository).search("果実", "獺祭", "純米", 1500, 2999, "甘口", pageable);
    }

    // 空白だけの銘柄名は、検索条件なしのnullとして扱うことを確認する。
    @Test
    void blankNameDoesNotRestrictExistingSearch() {
        var pageable = PageRequest.of(0, 6, Sort.by(Sort.Direction.ASC, "id"));
        when(repository.search(null, null, null, null, null, null, pageable))
                .thenReturn(Page.empty(pageable));

        service.search("", "   ", "", null, null, "", "recommended", 0);

        verify(repository).search(null, null, null, null, null, null, pageable);
    }
}
