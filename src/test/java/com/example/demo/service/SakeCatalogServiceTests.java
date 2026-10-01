package com.example.demo.service;

import com.example.demo.repository.SakeRepository;
import com.example.demo.repository.SakeTagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.mockito.Mockito.*;

class SakeCatalogServiceTests {
    private final SakeRepository repository = mock(SakeRepository.class);
    private final SakeCatalogService service = new SakeCatalogService(
            repository, mock(SakeTagRepository.class));

    @Test
    void featuredHandlesEmptyCatalogWithoutLoadingDetails() {
        when(repository.findAllIds()).thenReturn(java.util.List.of());
        org.assertj.core.api.Assertions.assertThat(service.featured()).isEmpty();
        verify(repository, never()).findAllById(any());
    }

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

    @Test
    void combinesTrimmedNameWithOtherFilters() {
        var pageable = PageRequest.of(1, 6,
                Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id")));
        when(repository.search("果実", "獺祭", "純米", 1500, 2999, "甘口", pageable))
                .thenReturn(Page.empty(pageable));

        service.search(" 果実 ", " 獺祭 ", "純米", 1500, 2999, "甘口", "priceAsc", 1);

        verify(repository).search("果実", "獺祭", "純米", 1500, 2999, "甘口", pageable);
    }

    @Test
    void blankNameDoesNotRestrictExistingSearch() {
        var pageable = PageRequest.of(0, 6, Sort.by(Sort.Direction.ASC, "id"));
        when(repository.search(null, null, null, null, null, null, pageable))
                .thenReturn(Page.empty(pageable));

        service.search("", "   ", "", null, null, "", "recommended", 0);

        verify(repository).search(null, null, null, null, null, null, pageable);
    }
}
