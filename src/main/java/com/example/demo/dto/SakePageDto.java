package com.example.demo.dto;

import com.example.demo.model.Sake;
import java.util.List;

public record SakePageDto(List<Sake> content, int pageNumber, int totalPages, long totalElements) {
    public boolean hasPrevious() { return pageNumber > 0; }
    public boolean hasNext() { return pageNumber + 1 < totalPages; }
}
