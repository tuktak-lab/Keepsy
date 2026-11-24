package com.tuktaklab.keepsy.customer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByShopIdAndPhone(long shopId, String phone);

    boolean existsByIdAndShopIdAndStatus(long id, long shopId, Customer.Status status);
}
