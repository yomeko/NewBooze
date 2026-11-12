package com.example.demo.controller;

import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.OutboundClickService;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/** 本人の操作を記録してから、登録済みの公式サイトを新しいタブで開く。 */
@Controller
public class OutboundClickController {
    private static final Logger log = LoggerFactory.getLogger(OutboundClickController.class);
    private final OutboundClickService clicks;

    public OutboundClickController(OutboundClickService clicks) {
        this.clicks = clicks;
    }

    // リンクの先読みやクローラーのGETでは件数を増やさない。
    @GetMapping("/sake/outbound")
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.POST).build();
    }

    @PostMapping("/sake/outbound")
    public Object follow(@RequestParam String source, @RequestParam String target,
            @RequestParam String kind, @AuthenticationPrincipal CustomUserDetails principal) {
        try {
            String destination = clicks.follow(source, target, kind,
                    principal == null ? null : principal.getUserId());
            // 303により外部サイトにはGETだけを送り、フォームや会員IDを送信しない。
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(destination))
                    .cacheControl(CacheControl.noStore()).build();
        } catch (DataAccessException | TransactionException ex) {
            log.warn("公式サイトへのクリックを記録できませんでした: {}", ex.getClass().getSimpleName());
            var error = new ModelAndView("outbound-error");
            error.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
            error.addObject("source", source);
            error.addObject("target", target);
            error.addObject("kind", kind);
            error.addObject("backUrl", backUrl(source, target));
            return error;
        }
    }

    private static String backUrl(String source, String target) {
        if ("guide".equals(source)) return "/guide";
        if ("detail".equals(source)) {
            try {
                long id = Long.parseLong(target);
                if (id > 0) return "/sake/" + id;
            } catch (NumberFormatException ignored) {
                // 外部入力を戻り先のURLとして使用しない。
            }
        }
        return "/";
    }
}
