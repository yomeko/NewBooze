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

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DiagnosisTests {
    @Autowired MockMvc mvc;
    @Autowired DiagnosisService diagnosis;

    @Test void quizRendersProgressAndExplicitNavigation() throws Exception {
        mvc.perform(get("/diagnosis")).andExpect(status().isOk())
                .andExpect(content().string(containsString("全4問中 1問目")))
                .andExpect(content().string(containsString("id=\"quiz-back\"")))
                .andExpect(content().string(containsString("id=\"quiz-next\"")));
    }

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

    @Test void emptyAnswersReturnToQuiz() throws Exception {
        mvc.perform(post("/diagnosis/result").with(csrf())).andExpect(redirectedUrl("/diagnosis"));
    }
}
