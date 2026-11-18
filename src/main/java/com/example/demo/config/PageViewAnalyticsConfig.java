package com.example.demo.config;

import com.example.demo.service.AnalyticsRequestPolicy;
import com.example.demo.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** HTMLの表示が成功した場合だけ、検索条件等の個人入力を保存せずPVを加算する。 */
@Configuration
public class PageViewAnalyticsConfig implements WebMvcConfigurer {
    private final PageViewInterceptor interceptor;

    public PageViewAnalyticsConfig(AnalyticsService analytics) {
        this.interceptor = new PageViewInterceptor(analytics);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }

    public static final class PageViewInterceptor implements HandlerInterceptor {
        private static final Logger log = LoggerFactory.getLogger(PageViewInterceptor.class);
        private static final String PAGE_ATTRIBUTE = PageViewInterceptor.class.getName() + ".page";
        private static final Set<String> PAGES = Set.of("/", "/guide", "/search", "/brewery-map",
                "/categories", "/tags", "/about", "/privacy", "/external-transmission", "/contact",
                "/diagnosis", "/diagnosis/result", "/ranking", "/pairings", "/goods", "/posts",
                "/mypage", "/mypage/account", "/mypage/diagnosis-history");
        private final AnalyticsService analytics;

        public PageViewInterceptor(AnalyticsService analytics) {
            this.analytics = analytics;
        }

        @Override
        public void postHandle(HttpServletRequest request, HttpServletResponse response,
                Object handler, ModelAndView view) {
            if (!(handler instanceof HandlerMethod) || view == null || view.getViewName() == null
                    || view.getViewName().startsWith("redirect:") || view.getViewName().startsWith("forward:")
                    || !AnalyticsRequestPolicy.isHumanNavigation(request)) return;
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (PAGES.contains(path) || path.matches("/sake/[1-9][0-9]*")) {
                request.setAttribute(PAGE_ATTRIBUTE, path);
            }
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception ex) {
            Object page = request.getAttribute(PAGE_ATTRIBUTE);
            String type = response.getContentType();
            if (page == null || ex != null || response.getStatus() != 200
                    || type == null || !type.startsWith("text/html")) return;
            try {
                analytics.recordPageView((String) page);
            } catch (DataAccessException | TransactionException failure) {
                log.warn("ページ閲覧を集計できませんでした ({})", failure.getClass().getSimpleName());
            }
        }
    }
}
