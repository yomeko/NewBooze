package com.example.demo.controller;

import com.example.demo.service.DiagnosisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

    // 診断画面に質問の進捗と戻る・次への操作が出ることを確認する。
    @Test void quizRendersProgressAndExplicitNavigation() throws Exception {
        mvc.perform(get("/diagnosis")).andExpect(status().isOk())
                .andExpect(content().string(containsString("全4問中 1問目")))
                .andExpect(content().string(containsString("id=\"quiz-back\"")))
                .andExpect(content().string(containsString("id=\"quiz-next\"")));
    }

    // 各質問の最小IDの選択肢を送り、加点結果・回答説明・おすすめ理由・比較カードを確認する。
    @Test void resultRendersWeightedTendenciesAnswerLinksAndComparableCards() throws Exception {
        String[] choices = diagnosis.questions().stream()
                .map(question -> question.choices().stream().min(java.util.Comparator.comparing(com.example.demo.dto.DiagnosisChoiceView::id)).orElseThrow().id().toString()).toArray(String[]::new);
        mvc.perform(post("/diagnosis/result").with(csrf()).param("choice", choices))
                .andExpect(status().isOk())
                .andExpect(view().name("diagnosis-result"))
                .andExpect(content().string(containsString("回答からのスコア：8")))
                .andExpect(content().string(containsString("果物のように華やかな香り")))
                .andExpect(content().string(containsString("おすすめの理由：")))
                .andExpect(content().string(containsString("class=\"taste-indicators\"")))
                .andExpect(content().string(containsString("class=\"sake-card-image\"")));
    }

    // 回答を送らなかった場合は、結果を出さず質問画面へ戻ることを確認する。
    @Test void emptyAnswersReturnToQuiz() throws Exception {
        mvc.perform(post("/diagnosis/result").with(csrf())).andExpect(redirectedUrl("/diagnosis"));
    }
}
