package com.example.demo.service;

import com.example.demo.entity.Sake;
import com.example.demo.entity.SakeType;
import com.example.demo.repository.SakeRepository;
import com.example.demo.repository.SakeTagRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ホーム・検索・診断・詳細の共通変換で、元データを保持したまま公開用URLを制御する。 */
class ProductImageCatalogTests {
    @Test void everyCatalogPathOmitsUnapprovedImageWithoutChangingStoredUrl() throws Exception {
        String original = "https://dassai.com/files/dassai45.jpg";
        var entity = new Sake();
        entity.setId(1L);
        entity.setName("獺祭 純米大吟醸45");
        entity.setSakeType(new SakeType(1L, "純米大吟醸"));
        entity.setImageUrl(original);
        var repository = mock(SakeRepository.class);
        var tags = mock(SakeTagRepository.class);
        when(tags.findByIdSakeIdIn(any())).thenReturn(List.of());
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(repository.findAll(any(Sort.class))).thenReturn(List.of(entity));
        when(repository.findAllIds()).thenReturn(List.of(1L));
        when(repository.findAllById(any())).thenReturn(List.of(entity));
        when(repository.search(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));
        var service = new SakeCatalogService(repository, tags, new ProductImagePublicationService(
                new ObjectMapper(), new ClassPathResource("data/product-image-publication.json")));

        assertThat(service.findById(1).orElseThrow().imageUrl()).isNull();
        assertThat(service.search("", "", "", null, null, "", "recommended", 0)
                .content().getFirst().imageUrl()).isNull();
        assertThat(service.featured().getFirst().imageUrl()).isNull();
        assertThat(service.all().getFirst().imageUrl()).isNull();
        assertThat(entity.getImageUrl()).isEqualTo(original);
        verify(repository, never()).save(any());
        verify(repository, never()).saveAll(any());
    }
}
