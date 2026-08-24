package com.example.demo.controller;

import com.example.demo.service.DiagnosisService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DiagnosisController {
    private final DiagnosisService diagnosisService;
    public DiagnosisController(DiagnosisService diagnosisService) { this.diagnosisService = diagnosisService; }
    @GetMapping("/diagnosis")
    public String diagnosis(Model model) { model.addAttribute("questions", diagnosisService.questions()); return "diagnosis"; }
    @PostMapping("/diagnosis/result")
    public String result(@RequestParam(name = "choice", required = false) List<Integer> choices, Model model) {
        Map<String, Integer> preferences = diagnosisService.preferenceFor(choices);
        if (preferences.isEmpty()) return "redirect:/diagnosis";
        model.addAttribute("preferences", preferences);
        model.addAttribute("recommendations", diagnosisService.recommend(preferences));
        return "diagnosis-result";
    }
}
