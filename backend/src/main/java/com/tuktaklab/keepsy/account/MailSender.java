package com.tuktaklab.keepsy.account;

/**
 * 메일 발송 경계 (FR-ACC-06, F49). 메일 발송 수단이 정해질 때까지 구현은 가상 메일함에 쌓는 {@link VirtualMailSender}뿐이다. 수단이
 * 정해지면 이 구현만 바꾸고 부르는 쪽은 그대로 둔다 (메시지 발송 경계 MessageSender와 같은 구조, A6). 구현이 하나인 인터페이스지만 교체가 예정돼 있어 둔다
 * (DEVELOPMENT 4장).
 */
interface MailSender {

    /** 부르는 쪽 트랜잭션에서 함께 커밋된다 */
    void send(long shopId, String recipientEmail, String subject, String body);
}
