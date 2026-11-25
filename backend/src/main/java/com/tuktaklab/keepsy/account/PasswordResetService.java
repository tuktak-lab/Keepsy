package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 비밀번호 재설정 (FR-ACC-06, P2, C3, F14 · F49) */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    /** 링크 유효 시간 (C3) */
    static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    static final String SUBJECT = "[Keepsy] 비밀번호 재설정";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShopRepository shopRepository;
    private final PasswordResetTokenRepository tokens;
    private final VirtualMailRepository mails;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public record MailItem(long id, String subject, String body, OffsetDateTime sentAt) {}

    /**
     * 가입된 이메일이면 토큰을 만들고 재설정 링크 메일을 보낸다. 가입 여부와 상관없이 조용히 끝나 응답으로 가입 여부를 알 수 없다 (F49). 토큰 원문은 메일 본문에만
     * 있고 저장 · 로그에 남기지 않는다.
     */
    @Transactional
    public void request(String email) {
        shopRepository
                .findByEmail(email)
                .ifPresent(shop -> {
                    String token = newToken();
                    tokens.save(
                            PasswordResetToken.issue(
                                    shop.getId(),
                                    hash(token),
                                    LocalDateTime.now(clock).plus(TOKEN_TTL)));
                    mailSender.send(
                            shop.getId(),
                            shop.getEmail(),
                            SUBJECT,
                            "아래 링크에서 30분 안에 새 비밀번호를 정해 주세요.\n"
                                    + "/password-reset?token="
                                    + token
                                    + "\n요청하지 않았다면 이 메일을 무시해 주세요.");
                });
    }

    /**
     * 토큰을 한 번만 쓰고 새 비밀번호를 저장한다 (F14). 성공하면 로그인 잠금을 풀고 그 매장의 다른 토큰도 쓸 수 없게 한다 (F49).
     *
     * @throws ApiException 없는 · 만료된 · 이미 쓴 토큰이면 구분하지 않고 {@code invalid}
     */
    @Transactional
    public void reset(String token, String newPassword) {
        LocalDateTime now = LocalDateTime.now(clock);
        PasswordResetToken row =
                tokens.findByTokenHash(hash(token)).orElseThrow(PasswordResetService::badToken);
        if (tokens.useIfValid(row.getId(), now) != 1) {
            throw badToken();
        }
        Shop shop =
                shopRepository
                        .findById(row.getShopId())
                        .orElseThrow(PasswordResetService::badToken);
        shop.resetPassword(passwordEncoder.encode(newPassword));
        tokens.invalidateUnused(shop.getId(), now);
    }

    /** 세션 매장의 계정 이메일로 온 메일만, 최신순 (F49) */
    @Transactional(readOnly = true)
    public PageResponse<MailItem> mailbox(long shopId, int page, int size) {
        Shop shop =
                shopRepository
                        .findById(shopId)
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return PageResponse.of(
                mails.findByShopIdAndRecipientEmailOrderBySentAtDescIdDesc(
                        shopId, shop.getEmail(), PageRequest.of(page, size)),
                m ->
                        new MailItem(
                                m.getId(),
                                m.getSubject(),
                                m.getBody(),
                                TimeConfig.offset(m.getSentAt())));
    }

    /** 무작위 32바이트, URL에 그대로 쓸 수 있는 base64url */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex. 토큰으로 행을 찾아야 해서 BCrypt 대신 쓴다 (무작위 32바이트라 추측 공격에 충분) */
    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256은 모든 JVM에 있다", e);
        }
    }

    private static ApiException badToken() {
        String reason = "재설정 링크가 만료됐거나 이미 사용됐습니다. 다시 요청해 주세요";
        return new ApiException(
                ErrorCode.INVALID, reason, List.of(new FieldViolation("token", reason)));
    }
}
