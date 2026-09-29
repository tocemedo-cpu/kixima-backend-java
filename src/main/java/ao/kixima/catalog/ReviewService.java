package ao.kixima.catalog;

import ao.kixima.catalog.dto.ReviewDto;
import ao.kixima.catalog.dto.ReviewSummaryDto;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/reviewService.js — avaliações reais (média +
 * contagem) de um produto, recalculadas a partir de {@link Review} sempre
 * que uma é criada/actualizada (upsert por `@@unique([productId, userId])`).
 */
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public ReviewService(ReviewRepository reviewRepository, ProductRepository productRepository,
                          UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ReviewSummaryDto addReview(String productId, String userId, int rating, String comment) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new NotFoundException("Produto"));
        Review review = reviewRepository.findByProductIdAndUserId(productId, userId).orElse(null);
        if (review == null) {
            review = new Review(UUID.randomUUID().toString(), productId, userId, rating, comment, Instant.now());
            reviewRepository.save(review);
        } else {
            review.setRating(rating);
            review.setComment(comment);
        }

        long count = reviewRepository.countByProductId(productId);
        Double media = reviewRepository.avgRatingByProductId(productId);
        Float rounded = media == null ? null : Math.round(media * 10f) / 10f;

        product.setRating(rounded);
        product.setReviewCount((int) count);

        return new ReviewSummaryDto(rounded, (int) count);
    }

    @Transactional(readOnly = true)
    public List<ReviewDto> listForProduct(String productId) {
        List<Review> reviews = reviewRepository.findByProductIdOrderByCreatedAtDesc(productId);
        Map<String, String> nomes = userRepository.findAllById(reviews.stream().map(Review::getUserId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(User::getId, User::getName));
        return reviews.stream()
                .map(r -> new ReviewDto(r.getId(), r.getProductId(), r.getUserId(), nomes.get(r.getUserId()),
                        r.getRating(), r.getComment(), r.getCreatedAt()))
                .toList();
    }
}
