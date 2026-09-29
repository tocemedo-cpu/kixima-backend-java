package ao.kixima.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuoteItemRepository extends JpaRepository<QuoteItem, String> {

    List<QuoteItem> findByQuoteRequestIdIn(List<String> quoteRequestIds);
}
