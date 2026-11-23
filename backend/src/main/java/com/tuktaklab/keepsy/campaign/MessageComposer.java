package com.tuktaklab.keepsy.campaign;

import java.util.Objects;

/**
 * 광고 표기 자동 삽입과 바이트 계산 (FR-CMP-04 · 05, 결정체크리스트 D5).
 * 값만으로 결과가 정해지는 규칙이라 DB · 스프링 없이 단위 테스트한다 (DEVELOPMENT 4장).
 *
 * <p>광고 표기는 서버가 항상 붙이므로 사용자가 본문에서 지우거나 "(광 고)"처럼 바꿔도 최종 본문에 남는다
 * (CONTRACTS 1장). 문서에 없던 세부는 2026-11-23 팀 결정을 따른다: 바이트는 광고 표기를 붙인 최종 본문 기준,
 * ASCII는 1바이트 · 그 밖의 문자(한글 · 특수문자 · 이모지)는 2바이트, 줄바꿈은 \n 하나로 맞춰 1바이트.
 */
public final class MessageComposer {

    private MessageComposer() {
    }

    /**
     * @param kind   발송 메시지 종류
     * @param body   변수를 치환한 본문
     * @param sender 광고 표기에 쓸 매장 정보. 광고({@link MessageKind#AD})일 때만 필요하고 나머지는 {@code null}이어도 된다
     */
    public static ComposedMessage compose(MessageKind kind, String body, AdSender sender) {
        Objects.requireNonNull(kind, "kind");
        String text = normalizeNewlines(Objects.requireNonNull(body, "body"));
        if (kind == MessageKind.AD) {
            Objects.requireNonNull(sender, "광고에는 매장 정보가 필요하다");
            // D5 형식: 앞 "(광고)매장명 전화번호", 끝 "무료수신거부 수신 거부 번호"
            text = "(광고)" + sender.shopName() + " " + sender.shopPhone() + "\n"
                    + text + "\n"
                    + "무료수신거부 " + sender.unsubscribeNumber();
        }
        return new ComposedMessage(text, countBytes(text));
    }

    /** ASCII 1바이트, 그 밖의 문자 2바이트. 줄바꿈은 \n 하나로 맞춰 1바이트 */
    public static int countBytes(String text) {
        return normalizeNewlines(text).codePoints().map(cp -> cp < 0x80 ? 1 : 2).sum();
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
