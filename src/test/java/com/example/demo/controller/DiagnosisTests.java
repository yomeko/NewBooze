package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.DiagnosisService;
import com.example.demo.service.SakeInteractionService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * アプリとDBを使い、診断画面の進捗と結果の表示を確認するテスト。
 * MockMvcでアクセスとフォーム送信を再現し、返されたHTMLや移動先を確認する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DiagnosisTests {
    @Autowired MockMvc mvc;
    @Autowired DiagnosisService diagnosis;
    @Autowired SakeInteractionService interactions;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    // 診断画面に質問の進捗と戻る・次への操作が出ることを確認する。
    @Test void quizRendersProgressAndExplicitNavigation() throws Exception {
        mvc.perform(get("/diagnosis")).andExpect(status().isOk())
                .andExpect(content().string(containsString("全6問中 1問目")))
                .andExpect(content().string(containsString("id=\"quiz-back\"")))
                .andExpect(content().string(containsString("id=\"quiz-next\"")));
    }

    // 各質問の最小IDの選択肢を送り、加点結果・回答説明・おすすめ理由・比較カードを確認する。
    @Test void resultRendersWeightedTendenciesAnswerLinksAndComparableCards() throws Exception {
        String[] choices = firstChoiceRoute().stream().map(String::valueOf).toArray(String[]::new);
        var submission = mvc.perform(post("/diagnosis/result").with(csrf()).param("choice", choices))
                .andExpect(redirectedUrlPattern("/diagnosis/result?resultId=*"))
                .andReturn();
        mvc.perform(openResult(submission))
                .andExpect(status().isOk())
                .andExpect(view().name("diagnosis-result"))
                .andExpect(content().string(containsString("味わい4象限")))
                .andExpect(content().string(containsString("果物のように華やかな香り")))
                .andExpect(content().string(containsString("おすすめの理由：")))
                .andExpect(content().string(containsString("class=\"taste-indicators\"")))
                .andExpect(content().string(containsString("class=\"sake-card-image\"")))
                .andExpect(content().string(not(containsString("/mypage/diagnosis/favorite"))))
                .andExpect(content().string(not(containsString("診断履歴で回答を確認する"))));
        mvc.perform(get(submission.getResponse().getRedirectedUrl()))
                .andExpect(redirectedUrl("/diagnosis"))
                .andExpect(flash().attribute("diagnosisError", containsString("もう一度診断してください")));
    }

    @Test void memberUpdatesFavoriteAndReturnsToTheSameDiagnosisCard() throws Exception {
        var owner = account();
        var other = account();
        var choices = firstChoiceRoute();
        long sakeId = diagnosis.recommend(diagnosis.aggregateAndPersist(choices, null)).getFirst().sake().id();
        var submission = mvc.perform(post("/diagnosis/result").with(user(owner)).with(csrf())
                        .param("choice", choices.stream().map(String::valueOf).toArray(String[]::new)))
                .andExpect(redirectedUrlPattern("/diagnosis/result?resultId=*"))
                .andReturn();
        String resultUrl = submission.getResponse().getRedirectedUrl();
        String cardUrl = resultUrl + "#diagnosis-favorite-" + sakeId;

        mvc.perform(openResult(submission).with(user(owner)))
                .andExpect(content().string(containsString("お気に入りに追加")));
        mvc.perform(favoriteFromResult(submission, sakeId, true).with(user(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(favoriteFromResult(submission, sakeId, true).with(user(other)).with(csrf()))
                .andExpect(redirectedUrl("/diagnosis"));
        assertFalse(interactions.isFavorite(owner.getUserId(), sakeId));
        assertFalse(interactions.isFavorite(other.getUserId(), sakeId));

        for (boolean selected : List.of(true, true, false, false)) {
            mvc.perform(favoriteFromResult(submission, sakeId, selected).with(user(owner)).with(csrf()))
                    .andExpect(redirectedUrl(cardUrl));
            assertEquals(selected, interactions.isFavorite(owner.getUserId(), sakeId));
            mvc.perform(openResult(submission).with(user(owner)))
                    .andExpect(content().string(containsString(selected ? "お気に入り登録済み（解除）" : "お気に入りに追加")));
        }
        mvc.perform(favoriteFromResult(submission, Long.MAX_VALUE, true).with(user(owner)).with(csrf()))
                .andExpect(redirectedUrl(resultUrl + "#diagnosis-recommendations"))
                .andExpect(flash().attribute("favoriteError", containsString("この銘柄は見つかりません")));
    }

    @Test void refreshingResultKeepsOneSavedDiagnosisAndChecksItsOwner() throws Exception {
        var owner = account();
        String[] choices = firstChoiceRoute().stream().map(String::valueOf).toArray(String[]::new);
        var submission = mvc.perform(post("/diagnosis/result").with(user(owner)).with(csrf())
                        .param("choice", choices))
                .andExpect(redirectedUrlPattern("/diagnosis/result?resultId=*"))
                .andReturn();

        for (int visit = 0; visit < 2; visit++) {
            mvc.perform(openResult(submission).with(user(owner)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("診断履歴で回答を確認する")));
        }
        entityManager.flush();
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_sessions WHERE user_id = ?",
                Long.class, owner.getUserId()));
        mvc.perform(openResult(submission).with(user(account())))
                .andExpect(redirectedUrl("/diagnosis"));
    }

    @Test void allNineBranchCombinationsHaveSixValidAnswers() {
        var bank = diagnosis.questions();
        var core = bank.stream().filter(q -> q.conditionChoiceId() == null).toList();
        org.junit.jupiter.api.Assertions.assertEquals(10, bank.size());
        for (var first : core.getFirst().choices()) {
            for (var sweet : core.getLast().choices()) {
                java.util.List<Long> selected = new java.util.ArrayList<>(java.util.List.of(
                        first.id(), core.get(1).choices().getFirst().id(), core.get(2).choices().getFirst().id(), sweet.id()));
                bank.stream().filter(q -> q.conditionChoiceId() != null && selected.contains(q.conditionChoiceId()))
                        .forEach(q -> selected.add(q.choices().getLast().id()));
                org.junit.jupiter.api.Assertions.assertEquals(6, selected.size());
                org.junit.jupiter.api.Assertions.assertTrue(diagnosis.validAnswers(selected));
                var incomplete = new java.util.ArrayList<>(selected);
                incomplete.removeLast();
                org.junit.jupiter.api.Assertions.assertFalse(diagnosis.validAnswers(incomplete));
                var duplicate = new java.util.ArrayList<>(selected);
                duplicate.add(selected.getFirst());
                org.junit.jupiter.api.Assertions.assertFalse(diagnosis.validAnswers(duplicate));
            }
        }
    }

    @Test void rejectsAnswersFromEveryBranch() throws Exception {
        String[] all = diagnosis.questions().stream().map(q -> q.choices().getFirst().id().toString()).toArray(String[]::new);
        mvc.perform(post("/diagnosis/result").with(csrf()).param("choice", all))
                .andExpect(view().name("diagnosis"));
    }

    // 回答を送らなかった場合は、結果を出さず質問画面へ戻ることを確認する。
    @Test void emptyAnswersReturnToQuiz() throws Exception {
        mvc.perform(post("/diagnosis/result").with(csrf())).andExpect(view().name("diagnosis"))
                .andExpect(content().string(containsString("回答を確認できませんでした")));
    }

    @Test void historyRequiresSignInAndExplainsEmptyHistory() throws Exception {
        mvc.perform(get("/mypage/diagnosis-history")).andExpect(redirectedUrl("/login"));

        var member = user(account());
        mvc.perform(get("/mypage").with(member)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/mypage/diagnosis-history\"")));
        mvc.perform(get("/mypage/diagnosis-history").with(member)).andExpect(status().isOk())
                .andExpect(content().string(containsString("まだ診断履歴がありません")));
        for (String page : List.of("-1", "1", "abc", "2147483647", "2147483648")) {
            mvc.perform(get("/mypage/diagnosis-history").param("page", page).with(member))
                    .andExpect(redirectedUrl("/mypage/diagnosis-history"));
        }
    }

    @Test void historyPagesRetainOldAnswersWithoutShowingAnotherMember() throws Exception {
        var owner = account();
        var other = account();
        var questions = diagnosis.questions();
        var current = firstChoiceRoute();
        // 分岐追加前に保存された4問の診断を再現する。
        var legacy = questions.stream().filter(question -> question.conditionChoiceId() == null)
                .map(question -> question.choices().getFirst().id()).toList();
        diagnosis.aggregateAndPersist(legacy, owner.getUserId());
        for (int i = 0; i < 9; i++) diagnosis.aggregateAndPersist(current, owner.getUserId());
        var submission = mvc.perform(post("/diagnosis/result").with(user(owner)).with(csrf())
                        .param("choice", current.stream().map(String::valueOf).toArray(String[]::new)))
                .andExpect(redirectedUrlPattern("/diagnosis/result?resultId=*"))
                .andReturn();
        mvc.perform(openResult(submission).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("診断履歴で回答を確認する")));

        var otherChoice = questions.getFirst().choices().getLast();
        diagnosis.aggregateAndPersist(List.of(otherChoice.id()), other.getUserId());
        entityManager.flush();
        // 保存時刻が同じ場合でも、古い4問の記録は次のページに表示される。
        jdbc.update("UPDATE diagnosis_sessions SET taken_at = ? WHERE user_id = ?",
                LocalDateTime.of(2026, 10, 14, 12, 0), owner.getUserId());
        entityManager.clear();

        mvc.perform(get("/mypage/diagnosis-history").param("userId", other.getUserId().toString()).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026/10/14 12:00:00")))
                .andExpect(content().string(containsString("6件の回答")))
                .andExpect(content().string(containsString("href=\"/mypage/diagnosis-history?page=1\"")))
                .andExpect(content().string(not(containsString("4件の回答"))))
                .andExpect(content().string(not(containsString(otherChoice.text()))));

        mvc.perform(get("/mypage/diagnosis-history").param("page", "1").with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("4件の回答")))
                .andExpect(content().string(containsString(questions.getFirst().choices().getFirst().text())))
                .andExpect(content().string(containsString("href=\"/mypage/diagnosis-history?page=0\"")))
                .andExpect(content().string(not(containsString("6件の回答"))));
    }

    private List<Long> firstChoiceRoute() {
        List<Long> selected = new ArrayList<>();
        diagnosis.questions().forEach(question -> {
            if (question.conditionChoiceId() == null || selected.contains(question.conditionChoiceId()))
                selected.add(question.choices().getFirst().id());
        });
        return selected;
    }

    private MockHttpServletRequestBuilder openResult(MvcResult submission) {
        return get(submission.getResponse().getRedirectedUrl())
                .session((MockHttpSession) submission.getRequest().getSession(false));
    }

    private MockHttpServletRequestBuilder favoriteFromResult(MvcResult submission, long sakeId, boolean selected) {
        var params = UriComponentsBuilder.fromUriString(submission.getResponse().getRedirectedUrl()).build().getQueryParams();
        return post("/mypage/diagnosis/favorite").session((MockHttpSession) submission.getRequest().getSession(false))
                .param("resultId", params.getFirst("resultId"))
                .param("sakeId", Long.toString(sakeId)).param("selected", Boolean.toString(selected));
    }

    private CustomUserDetails account() {
        User user = new User();
        user.setName("診断履歴の検証");
        user.setEmail(UUID.randomUUID() + "@example.invalid");
        user.setPasswordHash("{noop}unused-test-password");
        return new CustomUserDetails(users.saveAndFlush(user));
    }
}
