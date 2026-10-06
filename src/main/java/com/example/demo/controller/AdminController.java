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
    private final com.example.demo.service.SakeImageService images;
    private final com.example.demo.service.SakePageService pages;
    private final BreweryRepository breweries;
    private final TagRepository tags;
    private final SakeTagRepository sakeTags;

    public AdminController(UserRepository users, SakeRepository sake, SakeTypeRepository types, com.example.demo.service.SakeImageService images,
            com.example.demo.service.SakePageService pages, BreweryRepository breweries,
            TagRepository tags, SakeTagRepository sakeTags) {
        this.users = users;
        this.sake = sake;
        this.types = types;
        this.images = images;
        this.pages = pages;
        this.breweries = breweries;
        this.tags = tags;
        this.sakeTags = sakeTags;
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
        options(model);
        return "admin/sake-new";
    }

    /**
     * 入力チェックに加え、指定された酒種がDBにあるか確認してから銘柄を保存する。
     * 入力に問題があれば同じ画面を表示し、保存できたら登録画面へ移動して完了メッセージを出す。
     */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/sake/new")
    public String save(@Valid @ModelAttribute("sakeForm") AdminSakeForm form,
                       BindingResult errors, Model model, RedirectAttributes redirect) {
        var type = form.getSakeTypeId() == null ? java.util.Optional.<com.example.demo.entity.SakeType>empty()
                : types.findById(form.getSakeTypeId());
        if (type.isEmpty() && !errors.hasFieldErrors("sakeTypeId")) {
            errors.rejectValue("sakeTypeId", "invalid", "酒種を選択してください");
        }
        validateUrl(form.getOfficialUrl(), "officialUrl", errors);
        validateUrl(form.getPurchaseUrl(), "purchaseUrl", errors);
        var selectedIds = form.getTagIds() == null ? java.util.List.<Long>of()
                : form.getTagIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        var selectedTags = tags.findAllById(selectedIds);
        if (selectedTags.size() != selectedIds.size()) errors.rejectValue("tagIds", "invalid", "一覧にあるタグを選択してください");
        if (errors.hasErrors()) {
            options(model);
            return "admin/sake-new";
        }
        Sake item = new Sake();
        item.setName(form.getName().trim());
        item.setSakeType(type.orElseThrow());
        item.setRegion(form.getRegion() == null ? null : form.getRegion().trim());
        item.setAbv(form.getAbv());
        item.setPrice(form.getPrice());
        item.setDescription(form.getIntroduction() == null || form.getIntroduction().isBlank()
                ? form.getDescription() : form.getIntroduction());
        if (form.getImage() != null && !form.getImage().isEmpty()) {
            try {
                item.setImageUrl(images.store(form.getImage()));
            } catch (IllegalArgumentException | java.io.IOException ex) {
                errors.rejectValue("image", "invalid", ex instanceof IllegalArgumentException
                        ? ex.getMessage() : "画像を保存できませんでした。別の画像を選択して再試行してください");
                options(model);
                return "admin/sake-new";
            }
        }
        try {
            if (form.getBrewery() != null && !form.getBrewery().isBlank()) {
                String name = form.getBrewery().strip();
                item.setBrewery(breweries.findFirstByName(name).orElseGet(() -> {
                    var brewery = new com.example.demo.entity.Brewery();
                    brewery.setName(name);
                    brewery.setPrefecture(form.getRegion());
                    return breweries.save(brewery);
                }));
            }
            sake.saveAndFlush(item);
            pages.save(form.page(item.getId()));
            for (var tag : selectedTags) {
                var link = new com.example.demo.entity.SakeTag();
                link.setSake(item);
                link.setTag(tag);
                link.setScore((byte) 3);
                sakeTags.save(link);
            }
            sakeTags.flush();
        } catch (RuntimeException ex) {
            if (item.getImageUrl() != null) {
                try { images.delete(item.getImageUrl()); }
                catch (java.io.IOException cleanup) { ex.addSuppressed(cleanup); }
            }
            throw ex;
        }
        redirect.addFlashAttribute("registeredId", item.getId());
        redirect.addFlashAttribute("success", "日本酒「" + item.getName() + "」を登録しました。");
        return "redirect:/admin/sake/new";
    }
    private void options(Model model) {
        model.addAttribute("types", types.findAll(Sort.by("id")));
        model.addAttribute("tags", tags.findAll(Sort.by("category", "id")));
    }

    private void validateUrl(String url, String field, BindingResult errors) {
        if (url == null || url.isBlank()) return;
        try {
            new com.example.demo.model.SakePage.PurchaseLink("", url, true);
        } catch (IllegalArgumentException ex) {
            errors.rejectValue(field, "invalid", "https://で始まる有効なURLを入力してください");
        }
    }
}
