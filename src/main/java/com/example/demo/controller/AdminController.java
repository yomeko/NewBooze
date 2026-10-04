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

    @GetMapping
    public String index() { return "admin/index"; }

    @GetMapping("/users")
    public String users(@RequestParam(defaultValue = "") String keyword,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("users", users.findByNameContainingIgnoreCase(keyword.trim(),
                PageRequest.of(Math.max(0, page), 30, Sort.by("name").and(Sort.by("id")))));
        model.addAttribute("keyword", keyword);
        return "admin/users";
    }

    @GetMapping("/sake/new")
    public String form(Model model) {
        model.addAttribute("sakeForm", new AdminSakeForm());
        model.addAttribute("types", types.findAll(Sort.by("id")));
        return "admin/sake-new";
    }

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
