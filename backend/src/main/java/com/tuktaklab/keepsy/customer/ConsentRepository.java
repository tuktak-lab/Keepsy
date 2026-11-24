package com.tuktaklab.keepsy.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    List<Consent> findByCustomerIdOrderByIdAsc(long customerId);
}
