package com.example.demo.controller;

import com.example.demo.service.SakeImageService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SakeImageController {
    private final SakeImageService images;

    public SakeImageController(SakeImageService images) { this.images = images; }

    @GetMapping("/sake/images/{filename}")
    public ResponseEntity<byte[]> image(@PathVariable String filename) throws IOException {
        byte[] data = images.read(filename);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(filename.endsWith(".jpg") ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .body(data);
    }
}
