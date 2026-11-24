package com.tuktaklab.keepsy.customer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByShopIdAndPhone(long shopId, String phone);

    boolean existsByIdAndShopIdAndStatus(long id, long shopId, Customer.Status status);

    boolean existsByShopIdAndPhoneAndIdNot(long shopId, String phone, long id);

    Optional<Customer> findByIdAndShopIdAndStatus(long id, long shopId, Customer.Status status);
}
