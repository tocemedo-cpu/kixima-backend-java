package ao.kixima.marketplace;

import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.marketplace.dto.FavoriteResultDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Espelha backend/src/services/favoriteService.js — favoritos (coração) persistidos por utilizador. */
@Service
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;

    public FavoriteService(FavoriteRepository favoriteRepository, ProductRepository productRepository) {
        this.favoriteRepository = favoriteRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<String> listIds(String userId) {
        return favoriteRepository.findByUserId(userId).stream().map(Favorite::getProductId).toList();
    }

    @Transactional
    public FavoriteResultDto add(String userId, String productId) {
        if (!productRepository.existsById(productId)) throw new NotFoundException("Produto");
        if (favoriteRepository.findByUserIdAndProductId(userId, productId).isEmpty()) {
            favoriteRepository.save(new Favorite(UUID.randomUUID().toString(), userId, productId, Instant.now()));
        }
        return new FavoriteResultDto(productId, true);
    }

    @Transactional
    public FavoriteResultDto remove(String userId, String productId) {
        favoriteRepository.deleteByUserIdAndProductId(userId, productId);
        return new FavoriteResultDto(productId, false);
    }
}
