package com.tuktaklab.keepsy.customer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultNoteRepository extends JpaRepository<ConsultNote, Long> {

    Optional<ConsultNote> findByIdAndShopId(long id, long shopId);
}
