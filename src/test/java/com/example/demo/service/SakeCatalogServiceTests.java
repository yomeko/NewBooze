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
