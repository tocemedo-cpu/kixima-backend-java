package ao.kixima.marketplace;

import ao.kixima.marketplace.dto.SavedSearchDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Espelha o troço `/saved-searches` de backend/src/routes/marketplaceRoutes.js. */
@Service
public class SavedSearchService {

    private final SavedSearchRepository savedSearchRepository;

    public SavedSearchService(SavedSearchRepository savedSearchRepository) {
        this.savedSearchRepository = savedSearchRepository;
    }

    @Transactional(readOnly = true)
    public List<SavedSearchDto> listar(String userId) {
        return savedSearchRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toDto).toList();
    }

    @Transactional
    public SavedSearchDto criar(String userId, String label, String query) {
        String rotulo = (label == null || label.isBlank() ? "Pesquisa" : label);
        rotulo = rotulo.substring(0, Math.min(rotulo.length(), 120));
        String consulta = (query == null ? "" : query);
        consulta = consulta.substring(0, Math.min(consulta.length(), 500));
        SavedSearch guardada = new SavedSearch(UUID.randomUUID().toString(), userId, rotulo, consulta, Instant.now());
        savedSearchRepository.save(guardada);
        return toDto(guardada);
    }

    @Transactional
    public void remover(String userId, String id) {
        savedSearchRepository.deleteByIdAndUserId(id, userId);
    }

    private SavedSearchDto toDto(SavedSearch s) {
        return new SavedSearchDto(s.getId(), s.getUserId(), s.getLabel(), s.getQuery(), s.getCreatedAt());
    }
}
