package com.example.demo.controller;

import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.SakeInteractionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/mypage/sake/{id}")
public class SakeInteractionController {
    private final SakeInteractionService interactions;

    public SakeInteractionController(SakeInteractionService interactions) {
        this.interactions = interactions;
    }

    @PostMapping("/favorite")
    public String favorite(@PathVariable long id, @RequestParam boolean selected,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        interactions.favorite(principal.getUserId(), id, selected);
        redirect.addFlashAttribute("success", selected ? "お気に入りに登録しました。" : "お気に入りから削除しました。");
        return "redirect:/sake/" + id;
    }

    @PostMapping("/review")
    public String review(@PathVariable long id, @RequestParam int rating,
            @RequestParam(defaultValue = "") String comment,
            @RequestParam(defaultValue = "false") boolean published,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        try {
            interactions.saveReview(principal.getUserId(), id, rating, comment, published);
            redirect.addFlashAttribute("success", "評価を保存しました。");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            redirect.addFlashAttribute("review", new SakeInteractionService.Review(rating, comment, published));
        }
        return "redirect:/sake/" + id + "#review";
    }

    @PostMapping("/review/delete")
    public String delete(@PathVariable long id, @AuthenticationPrincipal CustomUserDetails principal,
            RedirectAttributes redirect) {
        interactions.deleteReview(principal.getUserId(), id);
        redirect.addFlashAttribute("success", "評価を削除しました。");
        return "redirect:/sake/" + id + "#review";
    }
}
