package ao.kixima.retention;

import ao.kixima.retention.dto.RetentionPolicyItemDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Espelha `app.get('/api/retencao', ...)` (app.js) — rota pública, fora de qualquer router com `authenticate`. */
@RestController
public class RetentionController {

    private final RetentionService retentionService;

    public RetentionController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @GetMapping("/api/retencao")
    public Map<String, List<RetentionPolicyItemDto>> politica() {
        return Map.of("politica", retentionService.politica());
    }
}
