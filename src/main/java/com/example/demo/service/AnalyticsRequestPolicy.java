package com.example.demo.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/** 閲覧・遷移集計からHEAD、先読み、明示的な自動巡回を除く共通条件。 */
public final class AnalyticsRequestPolicy {
    private AnalyticsRequestPolicy() {}

    public static boolean isHumanNavigation(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        for (String header : new String[]{"Purpose", "Sec-Purpose", "X-Purpose", "X-Moz"}) {
            String purpose = request.getHeader(header);
            if (purpose != null && (purpose.toLowerCase(Locale.ROOT).contains("prefetch")
                    || purpose.toLowerCase(Locale.ROOT).contains("prerender"))) return false;
        }
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) return true;
        String agent = userAgent.toLowerCase(Locale.ROOT);
        return !agent.contains("bot") && !agent.contains("spider") && !agent.contains("crawler")
                && !agent.contains("slurp") && !agent.contains("headless");
    }
}
