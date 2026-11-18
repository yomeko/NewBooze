package com.example.demo.config;

import com.example.demo.config.PageViewAnalyticsConfig.PageViewInterceptor;
import com.example.demo.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class PageViewAnalyticsTests {
    private final AnalyticsService analytics = mock(AnalyticsService.class);
    private final PageViewInterceptor interceptor = new PageViewInterceptor(analytics);
    private final HandlerMethod handler = mock(HandlerMethod.class);

    @ParameterizedTest
    @ValueSource(strings = {"/search", "/sake/42", "/mypage", "/diagnosis/result"})
    void countsSuccessfulHtmlViewsAndRemovesContextPath(String path) {
        var request = new MockHttpServletRequest("GET", "/app" + path);
        request.setContextPath("/app");
        var response = html();
        interceptor.postHandle(request, response, handler, new ModelAndView("search"));
        interceptor.afterCompletion(request, response, handler, null);
        verify(analytics).recordPageView(path);
    }

    @Test void excludesRenderFailuresRedirectViewsErrorStatusAndNonHtmlResponses() {
        for (int scenario = 0; scenario < 4; scenario++) {
            var request = new MockHttpServletRequest("GET", "/search");
            var response = html();
            interceptor.postHandle(request, response, handler,
                    new ModelAndView(scenario == 0 ? "redirect:/search" : "search"));
            if (scenario == 1) response.setStatus(500);
            if (scenario == 2) response.setContentType("application/json");
            interceptor.afterCompletion(request, response, handler,
                    scenario == 3 ? new IllegalStateException("render failed") : null);
        }
        verifyNoInteractions(analytics);
    }

    @Test void databaseAndCommitFailuresCannotBreakPageDisplay() {
        for (var failure : new RuntimeException[]{new DataAccessResourceFailureException("unavailable"),
                new TransactionSystemException("commit failed")}) {
            var request = new MockHttpServletRequest("GET", "/search");
            var response = html();
            doThrow(failure).when(analytics).recordPageView("/search");
            interceptor.postHandle(request, response, handler, new ModelAndView("search"));
            assertThatCode(() -> interceptor.afterCompletion(request, response, handler, null)).doesNotThrowAnyException();
        }
    }

    private MockHttpServletResponse html() {
        var response = new MockHttpServletResponse();
        response.setContentType("text/html;charset=UTF-8");
        return response;
    }
}
