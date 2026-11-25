package com.tuktaklab.keepsy.prediction;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CustomerPredictionRepository extends JpaRepository<CustomerPrediction, Long> {

    List<CustomerPrediction> findByShopIdAndCustomerIdIn(long shopId, Collection<Long> customerIds);

    @Modifying
    @Query(
            "delete from CustomerPrediction p where p.shopId = :shopId and p.customerId = :customerId")
    int deleteCustomer(long shopId, long customerId);
}
