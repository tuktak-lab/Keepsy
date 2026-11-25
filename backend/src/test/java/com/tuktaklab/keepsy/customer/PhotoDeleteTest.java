package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.support.TestShopData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사진 삭제 (F16): 행은 그 트랜잭션에서, 파일은 커밋 뒤에 지운다. 롤백되면 행 · 파일이 모두 남는다. 방문 삭제 · 동의 철회 · 고객 삭제가 모두 {@link
 * PhotoFileService}를 거친다. 실제 MySQL과 테스트 저장소에서 돈다.
 */
@SpringBootTest
class PhotoDeleteTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PhotoStorage storage;
    @Autowired PhotoFileService photoFiles;
    @Autowired TransactionTemplate tx;

    private TestShopData data;
    private long shop;
    private long customer;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (String key : keys) {
            storage.delete(key);
        }
        data.cleanUp();
    }

    @Test
    void 커밋하면_행과_파일을_지우고_다른_방문_사진은_남긴다() throws Exception {
        long visit = visit(LocalDate.of(2026, 1, 10));
        long otherVisit = visit(LocalDate.of(2026, 2, 10));
        String key = photo(visit);
        String otherKey = photo(otherVisit);

        tx.executeWithoutResult(s -> photoFiles.deleteVisitPhotos(shop, visit));

        assertThat(photoCount(visit)).isZero();
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);
        assertThat(photoCount(otherVisit)).isOne();
        try (InputStream in = storage.open(otherKey)) {
            assertThat(in.readAllBytes()).isNotEmpty();
        }

        tx.executeWithoutResult(s -> photoFiles.deleteCustomerPhotos(shop, customer));
        assertThat(photoCount(otherVisit)).isZero();
        assertThatThrownBy(() -> storage.open(otherKey)).isInstanceOf(IOException.class);
    }

    @Test
    void 롤백되면_행과_파일이_남는다() throws Exception {
        long visit = visit(LocalDate.of(2026, 1, 10));
        String key = photo(visit);

        assertThatThrownBy(() ->
                        tx.executeWithoutResult(s -> {
                            photoFiles.deleteCustomerPhotos(shop, customer);
                            throw new IllegalStateException("모의 오류");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(photoCount(visit)).isOne();
        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).isNotEmpty();
        }
    }

    private long visit(LocalDate date) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customer,
                date);
        return jdbc.queryForObject(
                "SELECT id FROM visit WHERE customer_id = ? AND visit_date = ?",
                Long.class,
                customer,
                date);
    }

    private String photo(long visitId) throws IOException {
        String key = storage.save(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        keys.add(key);
        jdbc.update(
                "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at) VALUES (?, ?, ?, ?)",
                shop,
                visitId,
                key,
                LocalDateTime.of(2026, 1, 10, 12, 0));
        return key;
    }

    private int photoCount(long visitId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM photo WHERE visit_id = ?", Integer.class, visitId);
    }
}
