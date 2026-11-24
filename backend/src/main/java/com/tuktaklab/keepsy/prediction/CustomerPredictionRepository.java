package com.tuktaklab.keepsy.prediction;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPredictionRepository extends JpaRepository<CustomerPrediction, Long> {

    List<CustomerPrediction> findByShopIdAndCustomerIdIn(long shopId, Collection<Long> customerIds);
}
