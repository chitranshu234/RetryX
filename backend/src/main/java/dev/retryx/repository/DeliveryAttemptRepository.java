package dev.retryx.repository;

import dev.retryx.domain.DeliveryAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The attempt log. Insert and read only — there is no update or delete path anywhere in the
 * application, which is what makes the delivery timeline trustworthy.
 */
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    /**
     * Timeline for the delivery-detail page.
     *
     * @param deliveryId delivery to read
     * @return attempts in the order they happened
     */
    List<DeliveryAttempt> findAllByDeliveryIdOrderByAttemptNumberAsc(UUID deliveryId);

    /**
     * @param deliveryId delivery to count
     * @return number of recorded attempts
     */
    long countByDeliveryId(UUID deliveryId);
}
