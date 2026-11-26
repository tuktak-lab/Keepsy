package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.campaign.MessageRenderer.Rendered;
import com.tuktaklab.keepsy.campaign.query.CampaignRecipientQuery.Recipient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 변수 치환 · 발송 불가 판정 (FR-CMP-03 · 05, 결정 F46 · F47). 모의 데이터만 쓴다 */
class MessageRendererTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final AdSender SENDER = new AdSender("모의 매장", "02-000-0000", "080-000-0000");

    private static Recipient recipient(String name, LocalDate lastServiceDate, String lastService) {
        return new Recipient(
                1L,
                false,
                false,
                name,
                LocalDateTime.of(2026, 3, 1, 9, 30),
                false,
                lastServiceDate,
                lastService,
                null);
    }

    @Test
    void 광고는_변수를_치환하고_광고_표기를_붙인다() {
        Rendered r =
                MessageRenderer.render(
                        "{고객명}님, {최근시술} 후 {경과일}일",
                        MessageKind.AD,
                        "모의 매장",
                        SENDER,
                        recipient("모의 고객", TODAY.minusDays(30), "젤"),
                        TODAY);

        String text = "(광고)모의 매장 02-000-0000\n모의 고객님, 젤 후 30일\n무료수신거부 080-000-0000";
        assertThat(r)
                .isEqualTo(
                        new Rendered(
                                text,
                                MessageComposer.countBytes(text),
                                MessageType.SMS,
                                List.of()));
    }

    @Test
    void 안내는_광고_표기_없이_동의일과_야간광고동의를_치환한다() {
        Rendered r =
                MessageRenderer.render(
                        "{동의일} 동의 (야간: {야간광고동의})",
                        MessageKind.CONSENT_RECONFIRM,
                        "모의 매장",
                        null,
                        recipient("모의 고객", null, null),
                        TODAY);

        assertThat(r.text()).isEqualTo("2026년 3월 1일 동의 (야간: 미동의)");
        assertThat(r.messageType()).isEqualTo(MessageType.SMS);
    }

    @Test
    void 값이_없는_변수는_처음_나온_순으로_한_번씩_모으고_본문은_null() {
        Rendered r =
                MessageRenderer.render(
                        "{경과일} {최근시술} {경과일} {모르는변수}",
                        MessageKind.AD,
                        "모의 매장",
                        SENDER,
                        recipient("모의 고객", null, null),
                        TODAY);

        assertThat(r).isEqualTo(new Rendered(null, null, null, List.of("경과일", "최근시술", "모르는변수")));
        assertThat(r.sendable()).isFalse();
    }

    @Test
    void 삭제된_고객은_변수가_없는_본문이어도_발송_불가() {
        Recipient deleted = new Recipient(1L, false, true, null, null, false, null, null, null);

        Rendered r =
                MessageRenderer.render("{매장명} 안내", MessageKind.AD, "모의 매장", SENDER, deleted, TODAY);

        assertThat(r).isEqualTo(new Rendered(null, null, null, List.of()));
    }

    @Test
    void 최종_본문이_2000바이트를_넘으면_본문과_바이트는_있지만_발송_불가() {
        Rendered r =
                MessageRenderer.render(
                        "가".repeat(990),
                        MessageKind.AD,
                        "모의 매장",
                        SENDER,
                        recipient("모의 고객", null, null),
                        TODAY);

        assertThat(r.text()).isNotNull();
        assertThat(r.bytes()).isGreaterThan(2_000);
        assertThat(r.messageType()).isNull();
    }

    @Test
    void 값에_든_달러와_역슬래시는_그대로_들어간다() {
        Rendered r =
                MessageRenderer.render(
                        "{고객명}님",
                        MessageKind.CONSENT_RECONFIRM,
                        "모의 매장",
                        null,
                        recipient("모의$1\\고객", null, null),
                        TODAY);

        assertThat(r.text()).isEqualTo("모의$1\\고객님");
    }
}
