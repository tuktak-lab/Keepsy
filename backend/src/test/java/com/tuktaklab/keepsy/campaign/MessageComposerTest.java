package com.tuktaklab.keepsy.campaign;

import static com.tuktaklab.keepsy.campaign.MessageKind.AD;
import static com.tuktaklab.keepsy.campaign.MessageKind.CONSENT_RECONFIRM;
import static com.tuktaklab.keepsy.campaign.MessageKind.UNSUBSCRIBE_NOTICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** FR-CMP-04 · 05, D5. 케이스는 Keepsy #91 「검증 방법」을 따른다. 예시 번호는 가짜 값이다. */
class MessageComposerTest {

    private static final AdSender SHOP = new AdSender("키프네일", "02-0000-0000", "080-000-0000");
    /** "(광고)키프네일 02-0000-0000\n" 28바이트 + "\n무료수신거부 080-000-0000" 26바이트 */
    private static final int AD_LABEL_BYTES = 54;

    @Test
    void 광고는_D5_형식으로_앞뒤에_표기를_붙인다() {
        ComposedMessage m = MessageComposer.compose(AD, "고객님, 젤네일 받으신 지 24일 지났어요.", SHOP);

        assertThat(m.text()).isEqualTo("""
                (광고)키프네일 02-0000-0000
                고객님, 젤네일 받으신 지 24일 지났어요.
                무료수신거부 080-000-0000""");
    }

    @Test
    void 사용자가_표기를_지우거나_바꿔도_최종_본문에는_정식_표기가_있다() {
        ComposedMessage m = MessageComposer.compose(AD, "(광 고) 이번 달 이벤트", SHOP);

        assertThat(m.text()).startsWith("(광고)키프네일 02-0000-0000\n");
        assertThat(m.text()).endsWith("\n무료수신거부 080-000-0000");
    }

    @Test
    void 수신_거부_처리_안내와_동의_재확인_안내에는_표기를_붙이지_않는다() {
        assertThat(MessageComposer.compose(UNSUBSCRIBE_NOTICE, "수신 거부가 처리되었습니다", null).text())
                .isEqualTo("수신 거부가 처리되었습니다");
        assertThat(MessageComposer.compose(CONSENT_RECONFIRM, "수신 동의를 다시 확인해 주세요", SHOP).text())
                .isEqualTo("수신 동의를 다시 확인해 주세요");
    }

    @Test
    void 광고에_매장_정보가_없으면_만들_수_없다() {
        assertThatThrownBy(() -> MessageComposer.compose(AD, "본문", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdSender("키프네일", " ", "080-000-0000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 바이트는_ASCII_1_그_밖의_문자_2() {
        assertThat(MessageComposer.countBytes("abc 123")).isEqualTo(7);
        assertThat(MessageComposer.countBytes("가나")).isEqualTo(4);
        assertThat(MessageComposer.countBytes("a가1")).isEqualTo(4);
        assertThat(MessageComposer.countBytes("·~★")).isEqualTo(1 + 2 + 2); // ~는 ASCII
        assertThat(MessageComposer.countBytes("😀")).isEqualTo(2); // 이모지도 문자 하나 = 2
    }

    @Test
    void 줄바꿈은_CRLF도_1바이트() {
        assertThat(MessageComposer.countBytes("가\r\n나\n다\r라")).isEqualTo(8 + 3);
        assertThat(MessageComposer.compose(UNSUBSCRIBE_NOTICE, "가\r\n나", null).text()).isEqualTo("가\n나");
    }

    @Test
    void 바이트_수는_광고_표기를_붙인_최종_본문_기준() {
        ComposedMessage m = MessageComposer.compose(AD, "", SHOP);

        assertThat(m.bytes()).isEqualTo(AD_LABEL_BYTES);
        assertThat(m.bytes()).isEqualTo(MessageComposer.countBytes(m.text()));
    }

    @Test
    void 경계_90바이트는_SMS_91바이트는_LMS() {
        assertThat(notice("가".repeat(45)).messageType()).contains(MessageType.SMS);          // 90
        assertThat(notice("가".repeat(44) + "a1").messageType()).contains(MessageType.SMS);   // 90
        assertThat(notice("가".repeat(45) + "a").messageType()).contains(MessageType.LMS);    // 91
    }

    @Test
    void 경계_2000바이트는_LMS_2001바이트는_발송_불가() {
        ComposedMessage atLimit = notice("가".repeat(999) + "a1");   // 2000
        ComposedMessage over = notice("가".repeat(1000) + "1");      // 2001

        assertThat(atLimit.messageType()).contains(MessageType.LMS);
        assertThat(atLimit.sendable()).isTrue();
        assertThat(over.bytes()).isEqualTo(2001);
        assertThat(over.messageType()).isEmpty();
        assertThat(over.sendable()).isFalse();
    }

    @Test
    void 광고는_표기_때문에_본문이_짧아도_LMS가_될_수_있다() {
        String body36 = "가".repeat(18);   // 54 + 36 = 90
        String body37 = body36 + "!";       // 91

        assertThat(MessageComposer.compose(AD, body36, SHOP).messageType()).contains(MessageType.SMS);
        assertThat(MessageComposer.compose(AD, body37, SHOP).messageType()).contains(MessageType.LMS);
    }

    private static ComposedMessage notice(String body) {
        return MessageComposer.compose(UNSUBSCRIBE_NOTICE, body, null);
    }
}
