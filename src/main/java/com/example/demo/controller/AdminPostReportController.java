package com.example.demo.controller;

import com.example.demo.service.DrinkPostService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** /admin 以下の既存の権限制御を使い、管理者だけが通報に対応する。 */
@Controller
@RequestMapping("/admin/post-reports")
public class AdminPostReportController {
    private final DrinkPostService posts;

    public AdminPostReportController(DrinkPostService posts) {
        this.posts = posts;
    }

    @GetMapping
    public String index(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("reports", posts.reportQueue(page));
        return "admin/post-reports";
    }

    @PostMapping("/{id}/dismiss")
    public String dismiss(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
            RedirectAttributes redirect) {
        posts.resolveReport(id, false);
        redirect.addFlashAttribute("success", "通報を対応済みにしました。");
        if (page > 0) redirect.addAttribute("page", page);
        return "redirect:/admin/post-reports";
    }

    @PostMapping("/{id}/delete-post")
    public String deletePost(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
            RedirectAttributes redirect) {
        posts.resolveReport(id, true);
        redirect.addFlashAttribute("success", "通報対象の投稿を削除しました。");
        if (page > 0) redirect.addAttribute("page", page);
        return "redirect:/admin/post-reports";
    }
}
