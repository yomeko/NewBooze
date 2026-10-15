package com.example.demo.dto;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class QuadrantTests {
    @Test void coversAllFourQuadrantsAndBoundaries() {
        for (String taste : new String[]{"甘口", "辛口"}) {
            for (String body : new String[]{"軽快", "濃醇"}) {
                var q = TastePresentation.quadrant(Map.of(taste, 5, body, 5));
                assertEquals((body.equals("軽快") ? "淡麗" : "濃醇") + taste, q.name());
                assertEquals(taste.equals("甘口") ? 10 : 90, q.x());
                assertEquals(body.equals("濃醇") ? 10 : 90, q.y());
            }
        }
        var neutral = TastePresentation.quadrant(Map.of("甘口", 2, "辛口", 2, "酸味", 5));
        assertEquals(50, neutral.x());
        assertEquals(50, neutral.y());
        assertTrue(neutral.name().contains("中間"));
        assertEquals(50, TastePresentation.quadrant(Map.of()).x());
    }
}
