package com.example.demo.controller;

import com.example.demo.dto.AdminSakeForm;
import com.example.demo.entity.Sake;
import com.example.demo.repository.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 管理者向けの画面を担当する。ユーザーを検索し、新しい日本酒を登録する。
 * Controllerは、ブラウザから届くURLや入力値を受け取り、表示するHTMLとそのデータを決める。
 * /admin以下へのアクセス権限はSecurityConfigで確認する。
 */
@Controller
@RequestMapping("/admin")
public class AdminController {
    private final UserRepository users;
    private final SakeRepository sake;
    private final SakeTypeRepository types;

    public AdminController(UserRepository users, SakeRepository sake, SakeTypeRepository types) {
        this.users = users;
        this.sake = sake;
        this.types = types;
    }

    /**
     * 管理メニューのHTML（templates/admin/index.html）を表示する。
     */
    @GetMapping
    public String index() { return "admin/index"; }

    /**
     * 入力された名前の一部で検索し、名前・ID順で30人ずつ表示する。
     * ページ番号は0始まり。負の番号が届いた場合は最初のページとして扱う。
     */
    @GetMapping("/users")
    public String users(@RequestParam(defaultValue = "") String keyword,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("users", users.findByNameContainingIgnoreCase(keyword.trim(),
                PageRequest.of(Math.max(0, page), 30, Sort.by("name").and(Sort.by("id")))));
        model.addAttribute("keyword", keyword);
        return "admin/users";
    }

    /**
     * 空の登録フォームと、選べる酒種の一覧を画面へ渡す。
     */
    @GetMapping("/sake/new")
    public String form(Model model) {
        model.addAttribute("sakeForm", new AdminSakeForm());
        model.addAttribute("types", types.findAll(Sort.by("id")));
        return "admin/sake-new";
    }

    /**
     * 入力チェックに加え、指定された酒種がDBにあるか確認してから銘柄を保存する。
     * 入力に問題があれば同じ画面を表示し、保存できたら登録画面へ移動して完了メッセージを出す。
     */
    @PostMapping("/sake/new")
    public String save(@Valid @ModelAttribute("sakeForm") AdminSakeForm form,
                       BindingResult errors, Model model, RedirectAttributes redirect) {
        var type = form.getSakeTypeId() == null ? java.util.Optional.<com.example.demo.entity.SakeType>empty()
                : types.findById(form.getSakeTypeId());
        if (type.isEmpty() && !errors.hasFieldErrors("sakeTypeId")) {
            errors.rejectValue("sakeTypeId", "invalid", "酒種を選択してください");
        }
        if (errors.hasErrors()) {
            model.addAttribute("types", types.findAll(Sort.by("id")));
            return "admin/sake-new";
        }
        Sake item = new Sake();
        item.setName(form.getName().trim());
        item.setSakeType(type.orElseThrow());
        item.setRegion(form.getRegion() == null ? null : form.getRegion().trim());
        item.setAbv(form.getAbv());
        item.setPrice(form.getPrice());
        item.setDescription(form.getDescription());
        sake.save(item);
        redirect.addFlashAttribute("success", "日本酒「" + item.getName() + "」を登録しました。");
        return "redirect:/admin/sake/new";
    }
}
