package com.tuktaklab.keepsy.customer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시술 사진 저장소 (NFR-07, 결정체크리스트 F16, ARCHITECTURE 7장). 저장 · 읽기 · 삭제 · 서명 URL 발급과 검증을 이 클래스 하나에 모아,
 * 운영하게 되면 S3 호환 저장소로 이 클래스만 바꾼다.
 *
 * <p>파일은 정적 리소스 밖의 비공개 디렉터리({@code APP_STORAGE_DIR})에 서버가 만든 무작위 키로 저장한다. URL은 사진 ID · 매장 ID · 만료
 * 시각(5분)을 {@code APP_STORAGE_SIGNING_KEY}로 HMAC-SHA256 서명한다. 현재 시각은 호출하는 쪽이 넘긴다.
 */
@Component
public class PhotoStorage {

    static final Duration URL_TTL = Duration.ofMinutes(5);
    private static final String HMAC = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final Path baseDir;
    private final SecretKeySpec signingKey;

    public PhotoStorage(
            @Value("${APP_STORAGE_DIR}") String dir,
            @Value("${APP_STORAGE_SIGNING_KEY}") String signingKey)
            throws IOException {
        byte[] key = signingKey.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "APP_STORAGE_SIGNING_KEY는 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다");
        }
        this.baseDir = Files.createDirectories(Path.of(dir).toAbsolutePath().normalize());
        this.signingKey = new SecretKeySpec(key, HMAC);
    }

    /** 저장하고 저장소 키를 돌려준다. 키는 무작위라 개인정보 · 사용자가 준 파일 이름이 들어가지 않는다. */
    public String save(InputStream in) throws IOException {
        String key = UUID.randomUUID().toString();
        Files.copy(in, resolve(key));
        return key;
    }

    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    /** 없는 파일이면 아무것도 하지 않는다. DB 커밋 뒤에 부른다 (F9, F16). */
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /**
     * {@code GET /api/photos/{photoId}/file?expires=&signature=} URL. 인증 · 권한 · 사진 동의는 호출하는 쪽이 먼저
     * 확인한다.
     */
    public String signedUrl(long photoId, long shopId, Instant now) {
        long expires = now.plus(URL_TTL).getEpochSecond();
        return "/api/photos/"
                + photoId
                + "/file?expires="
                + expires
                + "&signature="
                + sign(photoId, shopId, expires);
    }

    /** 서명(상수 시간 비교)과 만료를 확인한다. {@code shopId}는 세션의 매장이라, 다른 매장의 URL이면 서명이 맞지 않아 거부된다. */
    public boolean verify(long photoId, long shopId, long expires, String signature, Instant now) {
        if (signature == null || now.getEpochSecond() >= expires) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(photoId, shopId, expires).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(long photoId, long shopId, long expires) {
        try {
            Mac mac = Mac.getInstance(HMAC); // Mac은 스레드 안전하지 않아 호출마다 만든다
            mac.init(signingKey);
            byte[] digest =
                    mac.doFinal(
                            (photoId + ":" + shopId + ":" + expires)
                                    .getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 서명 실패", e);
        }
    }

    /** 경로를 정규화해 기준 디렉터리 밖이면 거부한다 (경로 조작 차단). */
    private Path resolve(String key) {
        Path path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir) || path.equals(baseDir)) {
            throw new IllegalArgumentException("저장소 키가 올바르지 않습니다");
        }
        return path;
    }
}
