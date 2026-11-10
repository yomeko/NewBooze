package com.example.demo.controller;

import com.example.demo.dto.DrinkPostForm;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.DrinkPostService;
import com.example.demo.service.DrinkPostService.ReportReason;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** フォームからは投稿IDと入力値だけを受け取り、操作する会員は認証情報で決める。 */
@Controller
@RequestMapping("/posts")
public class DrinkPostController {
    private final DrinkPostService posts;

    public DrinkPostController(DrinkPostService posts) {
        this.posts = posts;
    }

    @GetMapping
    public String index(@AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("drinkPostForm", new DrinkPostForm());
        return show(principal.getUserId(), page, model);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute DrinkPostForm drinkPostForm, BindingResult errors,
            @AuthenticationPrincipal CustomUserDetails principal, Model model,
            HttpServletResponse response, RedirectAttributes redirect) {
        if (!errors.hasErrors()) {
            try {
                posts.create(principal.getUserId(), drinkPostForm.getSakeName(), drinkPostForm.getComment());
                redirect.addFlashAttribute("success", "飲んだお酒を投稿しました。");
                return "redirect:/posts";
            } catch (IllegalArgumentException ex) {
                errors.reject("post.rejected", ex.getMessage());
            }
        }

        // 入力エラーではフォームの値を残し、投稿成功時だけGETへ戻して二重送信を防ぐ。
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return show(principal.getUserId(), 0, model);
    }

    @PostMapping("/{id}/like")
    public String like(@PathVariable Long id, @RequestParam boolean liked,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        if (!posts.setLiked(principal.getUserId(), id, liked)) return missingPost(page, redirect);
        return redirectToTimeline(page, redirect);
    }

    @PostMapping("/{id}/report")
    public String report(@PathVariable Long id, @RequestParam ReportReason reason,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        if (!posts.report(principal.getUserId(), id, reason)) return missingPost(page, redirect);
        redirect.addFlashAttribute("success", "投稿の通報を受け付けました。");
        return redirectToTimeline(page, redirect);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails principal,
            RedirectAttributes redirect) {
        if (!posts.deleteOwnPost(principal.getUserId(), id)) return missingPost(page, redirect);
        redirect.addFlashAttribute("success", "飲酒投稿を削除しました。");
        return redirectToTimeline(page, redirect);
    }

    private static String missingPost(int page, RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", "この投稿は見つかりません。削除された可能性があります。");
        return redirectToTimeline(page, redirect);
    }

    private static String redirectToTimeline(int page, RedirectAttributes redirect) {
        if (page > 0) redirect.addAttribute("page", page);
        return "redirect:/posts";
    }

    private String show(Long userId, int page, Model model) {
        model.addAttribute("timeline", posts.timeline(userId, page));
        model.addAttribute("reportReasons", ReportReason.values());
        return "drink-posts";
    }
}
