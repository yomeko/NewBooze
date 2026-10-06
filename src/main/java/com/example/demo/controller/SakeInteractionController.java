package com.example.demo.controller;

import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.SakeInteractionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 日本酒の詳細画面から届く、お気に入りとレビューの保存・削除を受け付ける。
 * 保存はSakeInteractionServiceへ任せ、終わったら同じ日本酒の詳細画面へ戻す。
 * /mypage以下なのでログインが必要で、操作対象の本人はprincipalから確認する。
 */
@Controller
@RequestMapping("/mypage/sake/{id}")
public class SakeInteractionController {
    private final SakeInteractionService interactions;

    public SakeInteractionController(SakeInteractionService interactions) {
        this.interactions = interactions;
    }

    /**
     * selected=trueでお気に入りに登録し、falseで解除する。操作結果は次の画面に表示する。
     */
    @PostMapping("/favorite")
    public String favorite(@PathVariable long id, @RequestParam boolean selected,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        interactions.favorite(principal.getUserId(), id, selected);
        redirect.addFlashAttribute("success", selected ? "お気に入りに登録しました。" : "お気に入りから削除しました。");
        return "redirect:/sake/" + id;
    }

    /**
     * 星・コメント・公開設定を保存する。
     * 入力に問題があれば、説明文と入力した値を次の画面へ渡して修正できるようにする。
     */
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

    /**
     * 本人のこの銘柄への評価とコメントを削除し、詳細画面のレビュー欄へ戻す。
     */
    @PostMapping("/review/delete")
    public String delete(@PathVariable long id, @AuthenticationPrincipal CustomUserDetails principal,
            RedirectAttributes redirect) {
        interactions.deleteReview(principal.getUserId(), id);
        redirect.addFlashAttribute("success", "評価を削除しました。");
        return "redirect:/sake/" + id + "#review";
    }
}
