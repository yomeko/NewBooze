package com.example.demo.model;

import java.util.List;

public record DiagnosisQuestion(int id, String text, List<DiagnosisChoice> choices) {
}
