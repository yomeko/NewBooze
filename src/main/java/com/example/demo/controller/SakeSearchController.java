package com.example.demo.controller;

import com.example.demo.service.SakeCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class SakeSearchController {
    private final SakeCatalogService catalogService;
    public SakeSearchController(SakeCatalogService catalogService) { this.catalogService = catalogService; }
    @GetMapping("/search")
    public String search(@RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) String type,
                         @RequestParam(required = false) String region, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("result", catalogService.search(keyword, type, region, page));
        model.addAttribute("types", catalogService.types());
        model.addAttribute("regions", catalogService.regions());
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedRegion", region);
        return "search";
    }
    @GetMapping("/sake/{id}")
    public String detail(@PathVariable long id, Model model) {
        model.addAttribute("sake", catalogService.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "detail";
    }
}
