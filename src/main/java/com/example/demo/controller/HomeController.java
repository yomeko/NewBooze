package com.example.demo.controller;

import com.example.demo.service.SakeCatalogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private final SakeCatalogService catalogService;
    public HomeController(SakeCatalogService catalogService) { this.catalogService = catalogService; }
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("featured", catalogService.featured());
        return "home";
    }
}
