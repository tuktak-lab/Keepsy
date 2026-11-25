package com.tuktaklab.keepsy.customer.port;

import com.tuktaklab.keepsy.account.ShopSettingsService;
import com.tuktaklab.keepsy.campaign.CampaignService;
import com.tuktaklab.keepsy.prediction.PredictionAppService;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.visit.VisitAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 각 도메인이 공개한 Service를 부른다 (DEVELOPMENT 4장 「도메인 연동」) */
@Component
@RequiredArgsConstructor
class CustomerDeletionReferencesAdapter implements CustomerDeletionReferences {

    private final ReservationAppService reservations;
    private final PredictionAppService predictions;
    private final CampaignService campaigns;
    private final ShopSettingsService shopSettings;
    private final VisitAppService visits;

    @Override
    public int confirmedReservationCount(long shopId, long customerId) {
        return reservations.confirmedCount(shopId, customerId);
    }

    @Override
    public void eraseElsewhere(long shopId, long customerId) {
        reservations.releaseForDeletedCustomer(shopId, customerId);
        predictions.deleteForCustomer(shopId, customerId);
        campaigns.eraseRecipient(shopId, customerId);
        visits.eraseNotesForDeletedCustomer(shopId, customerId);
    }

    @Override
    public int retentionYears(long shopId) {
        return shopSettings.settings(shopId).retentionYears();
    }
}
