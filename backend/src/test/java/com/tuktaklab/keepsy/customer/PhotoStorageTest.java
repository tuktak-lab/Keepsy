package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhotoStorageTest {

    private static final String KEY = "test-only-signing-key-32-bytes-long!!";
    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final Pattern URL =
            Pattern.compile("/api/photos/(\\d+)/file\\?expires=(\\d+)&signature=([\\w-]+)");

    @TempDir Path dir;
    private PhotoStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new PhotoStorage(dir.resolve("photos").toString(), KEY);
    }

    @Test
    void 저장_서명URL로_읽기_만료후_거부_삭제() throws IOException {
        String key = storage.save(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }

        Matcher url = URL.matcher(storage.signedUrl(7, 1, NOW));
        assertThat(url.matches()).isTrue();
        long expires = Long.parseLong(url.group(2));
        String signature = url.group(3);
        assertThat(expires).isEqualTo(NOW.plus(PhotoStorage.URL_TTL).getEpochSecond());

        assertThat(storage.verify(7, 1, expires, signature, NOW.plusSeconds(299))).isTrue();
        assertThat(storage.verify(7, 1, expires, signature, NOW.plusSeconds(300))).isFalse();

        storage.delete(key);
        assertThat(Files.list(dir.resolve("photos"))).isEmpty();
        storage.delete(key); // 이미 없어도 실패하지 않는다
    }

    @Test
    void 다른_매장_다른_사진_바뀐_만료_변조된_서명은_거부() {
        Matcher url = URL.matcher(storage.signedUrl(7, 1, NOW));
        assertThat(url.matches()).isTrue();
        long expires = Long.parseLong(url.group(2));
        String signature = url.group(3);

        assertThat(storage.verify(7, 2, expires, signature, NOW)).isFalse();
        assertThat(storage.verify(8, 1, expires, signature, NOW)).isFalse();
        assertThat(storage.verify(7, 1, expires + 3600, signature, NOW)).isFalse();
        assertThat(storage.verify(7, 1, expires, signature + "A", NOW)).isFalse();
        assertThat(storage.verify(7, 1, expires, null, NOW)).isFalse();
    }

    @Test
    void 다른_서명키로_만든_URL은_거부() throws IOException {
        PhotoStorage other =
                new PhotoStorage(dir.toString(), "another-test-signing-key-32-bytes!!");
        Matcher url = URL.matcher(other.signedUrl(7, 1, NOW));
        assertThat(url.matches()).isTrue();

        assertThat(storage.verify(7, 1, Long.parseLong(url.group(2)), url.group(3), NOW)).isFalse();
    }

    @Test
    void 기준_디렉터리_밖_경로는_거부() {
        assertThatThrownBy(() -> storage.open("../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("a/../../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(".")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 짧은_서명키는_시작_시_거부() {
        assertThatThrownBy(() -> new PhotoStorage(dir.toString(), "short"))
                .isInstanceOf(IllegalStateException.class);
    }
}
