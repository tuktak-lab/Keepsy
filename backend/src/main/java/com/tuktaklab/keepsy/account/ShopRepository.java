package com.tuktaklab.keepsy.account;

import org.springframework.data.jpa.repository.JpaRepository;

interface ShopRepository extends JpaRepository<Shop, Long> {

    boolean existsByEmail(String email);
}
