package com.example.demo.controller;

import com.example.demo.dto.AdminSakeForm;
import com.example.demo.entity.Sake;
import com.example.demo.entity.SakeTag;
import com.example.demo.entity.SakeType;
import com.example.demo.entity.Tag;
import com.example.demo.repository.*;
import com.example.demo.service.SakeImageService;
import com.example.demo.service.SakePageService;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 選択順、重複、入力エラー時の保持を、DBの取得順に依存せず確認する。 */
class AdminTagWeightTests {
    private final SakeRepository sake = mock(SakeRepository.class);
    private final SakeTypeRepository types = mock(SakeTypeRepository.class);
    private final TagRepository tags = mock(TagRepository.class);
    private final SakeTagRepository sakeTags = mock(SakeTagRepository.class);
    private final SakeImageService images = mock(SakeImageService.class);
    private final SakePageService pages = mock(SakePageService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var controller = new AdminController(mock(UserRepository.class), sake, types, images,
                pages, mock(BreweryRepository.class), tags, sakeTags);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(types.findById(1L)).thenReturn(Optional.of(new SakeType(1L, "純米")));
        when(sake.saveAndFlush(any(Sake.class))).thenAnswer(invocation -> {
            Sake item = invocation.getArgument(0);
            item.setId(100L);
            return item;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4, 30})
    void savesDescendingWeightsInSelectionOrderRegardlessOfRepositoryOrder(int count) throws Exception {
        var selectedIds = IntStream.rangeClosed(1, count).mapToObj(index -> (long) (count + 1 - index)).toList();
        // findAllByIdの返却順が、画面から受信した選択順と逆でも重みを維持する。
        when(tags.findAllById(selectedIds)).thenReturn(selectedIds.reversed().stream().map(this::tag).toList());

        mvc.perform(validRequest().param("tagIds", parameters(selectedIds)))
                .andExpect(redirectedUrl("/admin/sake/new"));

        var saved = ArgumentCaptor.forClass(SakeTag.class);
        verify(sakeTags, times(count)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(link -> link.getTag().getId()).containsExactlyElementsOf(selectedIds);
        assertThat(saved.getAllValues()).extracting(link -> link.getScore().intValue())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, count).map(index -> count + 1 - index).boxed().toList());
        assertThat(saved.getAllValues()).allSatisfy(link -> assertThat(link.getSake().getId()).isEqualTo(100L));
    }

    @Test void repeatedIdsKeepTheirFirstSelectionAndDoNotIncreaseWeights() throws Exception {
        when(tags.findAllById(List.of(3L, 1L, 2L))).thenReturn(List.of(tag(1L), tag(2L), tag(3L)));

        mvc.perform(validRequest().param("tagIds", "3", "1", "3", "2", "1"))
                .andExpect(redirectedUrl("/admin/sake/new"));

        var saved = ArgumentCaptor.forClass(SakeTag.class);
        verify(sakeTags, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(link -> link.getTag().getId()).containsExactly(3L, 1L, 2L);
        assertThat(saved.getAllValues()).extracting(SakeTag::getScore).containsExactly((byte) 3, (byte) 2, (byte) 1);
    }

    @Test void noTagsStillRegistersSakeWithoutLinks() throws Exception {
        mvc.perform(validRequest()).andExpect(redirectedUrl("/admin/sake/new"));
        verify(sake).saveAndFlush(any(Sake.class));
        verify(sakeTags, never()).save(any());
    }

    @Test void unknownTagRejectsRegistrationAndPreservesSelectionOrder() throws Exception {
        when(tags.findAllById(List.of(3L, 99L, 1L))).thenReturn(List.of(tag(1L), tag(3L)));

        mvc.perform(validRequest().param("tagIds", "3", "99", "1"))
                .andExpect(status().isOk()).andExpect(view().name("admin/sake-new"))
                .andExpect(model().attributeHasFieldErrors("sakeForm", "tagIds"))
                .andExpect(result -> assertThat(((AdminSakeForm) result.getModelAndView().getModel().get("sakeForm")).getTagIds())
                        .containsExactly(3L, 99L, 1L));
        verifyNoInteractions(sake, sakeTags, pages);
    }

    @Test void unrelatedValidationErrorPreservesSelectionOrder() throws Exception {
        when(tags.findAllById(List.of(3L, 1L, 2L))).thenReturn(List.of(tag(1L), tag(2L), tag(3L)));

        mvc.perform(validRequest().param("officialUrl", "javascript:alert(1)").param("tagIds", "3", "1", "2"))
                .andExpect(status().isOk()).andExpect(view().name("admin/sake-new"))
                .andExpect(model().attributeHasFieldErrors("sakeForm", "officialUrl"))
                .andExpect(result -> assertThat(((AdminSakeForm) result.getModelAndView().getModel().get("sakeForm")).getTagIds())
                        .containsExactly(3L, 1L, 2L));
        verifyNoInteractions(sake, sakeTags, pages);
    }

    @Test void imageValidationErrorPreservesSelectionOrder() throws Exception {
        when(tags.findAllById(List.of(3L, 1L, 2L))).thenReturn(List.of(tag(1L), tag(2L), tag(3L)));
        when(images.store(any())).thenThrow(new IllegalArgumentException("正しい画像を選択してください"));

        mvc.perform(multipart("/admin/sake/new")
                .file(new MockMultipartFile("image", "fake.png", "image/png", new byte[]{1}))
                .param("name", "重み付けテスト").param("sakeTypeId", "1").param("tagIds", "3", "1", "2"))
                .andExpect(status().isOk()).andExpect(view().name("admin/sake-new"))
                .andExpect(model().attributeHasFieldErrors("sakeForm", "image"))
                .andExpect(result -> assertThat(((AdminSakeForm) result.getModelAndView().getModel().get("sakeForm")).getTagIds())
                        .containsExactly(3L, 1L, 2L));
        verifyNoInteractions(sake, sakeTags, pages);
    }

    @Test void moreThanThirtyTagsIsRejectedBeforeSaving() throws Exception {
        var selectedIds = IntStream.rangeClosed(1, 31).mapToObj(index -> (long) index).toList();
        when(tags.findAllById(selectedIds)).thenReturn(selectedIds.stream().map(this::tag).toList());

        mvc.perform(validRequest().param("tagIds", parameters(selectedIds)))
                .andExpect(status().isOk()).andExpect(view().name("admin/sake-new"))
                .andExpect(model().attributeHasFieldErrors("sakeForm", "tagIds"));
        verifyNoInteractions(sake, sakeTags, pages);
    }

    private MockHttpServletRequestBuilder validRequest() {
        return post("/admin/sake/new").param("name", "重み付けテスト").param("sakeTypeId", "1");
    }

    private String[] parameters(List<Long> ids) {
        return ids.stream().map(String::valueOf).toArray(String[]::new);
    }

    private Tag tag(long id) {
        return new Tag(id, "タグ" + id, "味わい");
    }
}
