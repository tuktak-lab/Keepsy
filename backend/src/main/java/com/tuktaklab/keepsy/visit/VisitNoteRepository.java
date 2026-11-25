package com.tuktaklab.keepsy.visit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VisitNoteRepository extends JpaRepository<VisitNote, Long> {

    @Modifying
    @Query("delete from VisitNote n where n.shopId = :shopId and n.visitId = :visitId")
    void deleteVisitNotes(long shopId, long visitId);

    @Modifying
    @Query(
            "delete from VisitNote n where n.shopId = :shopId and n.visitId in"
                    + " (select v.id from Visit v where v.shopId = :shopId and v.customerId = :customerId)")
    void deleteCustomerVisitNotes(long shopId, long customerId);
}
