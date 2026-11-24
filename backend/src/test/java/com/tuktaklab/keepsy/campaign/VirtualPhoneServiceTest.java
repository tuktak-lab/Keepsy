package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 가상 수신함 번호 정규화 (CONTRACTS 6장 고객 phone 저장 형식). DB 없이 돈다. 가짜 번호만 쓴다. */
class VirtualPhoneServiceTest {

    @Test
    void 하이픈이_있든_없든_010_0000_0000_형식으로_맞춘다() {
        assertThat(VirtualPhoneService.normalize("01000007001")).isEqualTo("010-0000-7001");
        assertThat(VirtualPhoneService.normalize("010-0000-7001")).isEqualTo("010-0000-7001");
        assertThat(VirtualPhoneService.normalize("010-00007001")).isEqualTo("010-0000-7001");
    }
}
