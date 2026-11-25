package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 재설정 토큰 만들기 · 해시 (Keepsy #25, F14 · F49). DB 없이 돈다. */
class PasswordResetTokenTest {

    @Test
    void 토큰은_URL에_쓸_수_있는_무작위_32바이트이고_매번_다르다() {
        String a = PasswordResetService.newToken();
        String b = PasswordResetService.newToken();

        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 해시는_같은_토큰이면_같고_원문을_드러내지_않는_SHA256_hex다() {
        String token = PasswordResetService.newToken();

        assertThat(PasswordResetService.hash(token))
                .isEqualTo(PasswordResetService.hash(token))
                .hasSize(64)
                .matches("[0-9a-f]+")
                .doesNotContain(token);
        assertThat(PasswordResetService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
