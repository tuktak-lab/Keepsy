package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.support.TestShopData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 사진 업로드가 저장소에 쓴 뒤 DB가 롤백되면 파일을 지운다 (Keepsy #46, F9 · F16). 사진 행 INSERT 뒤의 접근 로그 기록을 실패시켜 롤백한다. 실제
 * MySQL과 테스트 저장소 디렉터리에서 돈다.
 */
@SpringBootTest
class PhotoUploadRollbackTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired PhotoUploadService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AccessLogger accessLogger;

    @Value("${APP_STORAGE_DIR}")
    String storageDir;

    private TestShopData data;

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 접근_로그에서_실패해_롤백되면_사진_행도_파일도_남지_않는다() throws Exception {
        data = new TestShopData(jdbc);
        long shop = data.shop();
        long staff = data.staff(shop, "ACTIVE", false);
        long customer = data.customer(shop, false, null);
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id, method)"
                        + " VALUES (?, ?, 'PHOTO', TRUE, ?, ?, 'TABLET')",
                shop,
                customer,
                LocalDateTime.now(),
                staff);
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customer,
                LocalDate.of(2026, 11, 1));
        long visit =
                jdbc.queryForObject(
                        "SELECT id FROM visit WHERE customer_id = ?", Long.class, customer);
        doThrow(new IllegalStateException("모의 실패"))
                .when(accessLogger)
                .record(any(), any(), anyLong(), any());
        long before = fileCount();

        assertThatThrownBy(() -> service.upload(shop, visit, PNG))
                .isInstanceOf(IllegalStateException.class);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM photo WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
        assertThat(fileCount()).isEqualTo(before);
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(storageDir))) {
            return files.count();
        }
    }
}
