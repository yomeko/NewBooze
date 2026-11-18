package com.example.demo.controller;

import com.example.demo.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 管理者トップの集計。権限はSecurityConfigの/admin以下のルールで確認する。 */
@Controller
public class AdminAnalyticsController {
    private static final Logger log = LoggerFactory.getLogger(AdminAnalyticsController.class);
    private final AnalyticsService analytics;

    public AdminAnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping({"/admin", "/admin/"})
    public String index(Model model) {
        try {
            model.addAttribute("analytics", analytics.dashboard());
            model.addAttribute("analyticsAvailable", true);
        } catch (DataAccessException | TransactionException ex) {
            // 集計の障害をゼロ件と表示せず、管理メニューは引き続き使えるようにする。
            log.warn("管理者向けアクセス集計を取得できませんでした ({})", ex.getClass().getSimpleName());
            model.addAttribute("analyticsAvailable", false);
        }
        return "admin/index";
    }
}
