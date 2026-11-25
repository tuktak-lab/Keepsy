package com.tuktaklab.keepsy.customer.port;

import java.time.LocalDate;
import java.util.List;

/** CSV 고객 일괄 등록이 방문 도메인에 맡기는 동작 (FR-CUS-13, DEVELOPMENT 4장 「도메인 연동」) */
public interface CustomerImportReferences {

    record ImportedVisit(long customerId, LocalDate visitDate, String category) {}

    /**
     * 가져온 방문 · 시술을 더하고 더해진 고객의 예측을 다시 계산한다 (C6, F50). 부르는 쪽 트랜잭션에서 함께 커밋된다.
     *
     * @return 항목마다 방문 · 시술을 더했는지, 같은 순서
     */
    List<Boolean> importVisits(long shopId, List<ImportedVisit> visits);
}
