package com.tuktaklab.keepsy.campaign;

import com.tuktaklab.keepsy.campaign.query.CampaignRecipientQuery.Recipient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 고객 한 명의 변수 치환 · 광고 표기 · 바이트 계산 (FR-CMP-03 · 04 · 05, 결정 F46 · F47). 미리보기와 발송이 같이 쓴다. 값만으로 결과가
 * 정해지므로 DB 없이 단위 테스트한다.
 */
public final class MessageRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]*)\\}");
    private static final DateTimeFormatter CONSENT_DATE =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일");

    private MessageRenderer() {}

    /**
     * @param text 광고 표기까지 붙인 최종 본문. 값 없는 변수가 있거나 삭제된 고객이면 null
     * @param bytes {@code text}의 바이트 수
     * @param messageType null이면 발송 불가 (2,000바이트 초과 · 변수 값 없음 · 삭제된 고객)
     * @param missingVariables 본문에 쓴 변수 중 값을 구할 수 없는 변수, 처음 나온 순
     */
    public record Rendered(
            String text, Integer bytes, MessageType messageType, List<String> missingVariables) {

        public boolean sendable() {
            return messageType != null;
        }
    }

    /**
     * @param sender 광고({@link MessageKind#AD})일 때만 필요하다 ({@code ShopContact#adSender})
     * @param today 오늘 (Asia/Seoul). {경과일} 계산에 쓴다. 마지막 시술일이 오늘보다 늦으면 {경과일}은 값 없음(발송 불가)이다
     */
    public static Rendered render(
            String body,
            MessageKind kind,
            String shopName,
            AdSender sender,
            Recipient r,
            LocalDate today) {
        Set<String> missing = new LinkedHashSet<>();
        String replaced =
                VARIABLE.matcher(body)
                        .replaceAll(match -> {
                            String value = value(match.group(1), shopName, r, today);
                            if (value == null) {
                                missing.add(match.group(1));
                                return "";
                            }
                            return Matcher.quoteReplacement(value);
                        });
        // 삭제된 고객은 변수와 관계없이 보내지 않고 지난 정보를 되살리지 않는다 (C12, F47)
        if (r.deleted() || !missing.isEmpty()) {
            return new Rendered(null, null, null, List.copyOf(missing));
        }
        ComposedMessage composed = MessageComposer.compose(kind, replaced, sender);
        return new Rendered(
                composed.text(), composed.bytes(), composed.messageType().orElse(null), List.of());
    }

    /** F46 변수 값. 구할 수 없거나 모르는 변수면 null */
    private static String value(String variable, String shopName, Recipient r, LocalDate today) {
        return switch (variable) {
            case "고객명" -> r.name();
            case "매장명" -> shopName;
            case "동의일" ->
                    r.marketingConsentAt() == null
                            ? null
                            : r.marketingConsentAt().format(CONSENT_DATE);
            case "야간광고동의" -> r.nightAdConsent() ? "동의" : "미동의";
            case "최근시술" -> r.lastServiceDate() == null ? null : r.lastServiceName();
            // 마지막 시술일이 오늘보다 늦으면 음수가 되므로 값 없음으로 본다 (F70, #411)
            case "경과일" ->
                    r.lastServiceDate() == null || r.lastServiceDate().isAfter(today)
                            ? null
                            : String.valueOf(ChronoUnit.DAYS.between(r.lastServiceDate(), today));
            default -> null;
        };
    }
}
