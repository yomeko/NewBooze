package com.example.demo.model;

import java.util.Map;

public record DiagnosisChoice(int id, String text, Map<String, Integer> tagWeights) {
}
