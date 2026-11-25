package com.tuktaklab.keepsy.customer.port;

import com.tuktaklab.keepsy.visit.VisitAppService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 방문은 visit 도메인이 공개한 {@link VisitAppService}로 만든다 */
@Component
@RequiredArgsConstructor
class VisitCustomerImportReferences implements CustomerImportReferences {

    private final VisitAppService visits;

    @Override
    public List<Boolean> importVisits(long shopId, List<ImportedVisit> items) {
        return visits.importVisits(
                shopId,
                items.stream()
                        .map(i ->
                                new VisitAppService.ImportedVisit(
                                        i.customerId(), i.visitDate(), i.category()))
                        .toList());
    }
}
