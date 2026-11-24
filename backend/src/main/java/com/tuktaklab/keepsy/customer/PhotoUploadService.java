package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 시술 사진 업로드 (FR-CUS-08, C4, F16, CONTRACTS 6장 「고객 (CUS)」) */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoUploadService {

    /** 장당 10MB (C4). 업로드 한도 설정(application.yaml)과 같은 값을 서버에서 다시 검사한다 (NFR-08) */
    static final long MAX_BYTES = 10L * 1024 * 1024;

    /** 방문 1회당 10장 (C4) */
    static final int MAX_PER_VISIT = 10;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final PhotoStorage storage;
    private final CustomerRepository customers;
    private final ConsentRepository consents;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessLogger accessLogger;
    private final Clock clock;

    /**
     * 사진 한 장을 저장소에 쓰고 방문에 붙인다. 저장소에 먼저 쓰고 DB가 롤백되면 파일을 지운다.
     *
     * @return 사진 ID
     * @throws ApiException 형식 · 크기 · 장수 위반은 {@code invalid}, 다른 매장 · 없는 방문 · 삭제된 고객의 방문은 {@code
     *     not_found}, 고객 없는 방문은 {@code conflict}, 사진 동의가 없으면 {@code consent_required}
     */
    @Transactional
    public long upload(long shopId, long visitId, byte[] file) throws IOException {
        if (file.length == 0 || file.length > MAX_BYTES) {
            throw invalid("사진은 10MB 이하로 올려 주세요");
        }
        if (!startsWith(file, JPEG) && !startsWith(file, PNG)) {
            throw invalid("JPG · PNG 사진만 올릴 수 있습니다");
        }
        MapSqlParameterSource p =
                new MapSqlParameterSource("visitId", visitId).addValue("shopId", shopId);
        List<Long> owner =
                jdbc.query(
                        "SELECT customer_id FROM visit WHERE id = :visitId AND shop_id = :shopId",
                        p,
                        (rs, i) -> rs.getObject("customer_id", Long.class));
        if (owner.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Long customerId = owner.get(0);
        if (customerId == null) {
            throw new ApiException(ErrorCode.CONFLICT, "고객 없음으로 마감한 방문에는 사진을 붙이지 않습니다");
        }
        // 사진 동의 철회(CustomerService#changeConsent)와 같은 고객 행 잠금: 장수 세기와 철회 뒤 사진 남김을 함께 막는다
        customers
                .findForUpdateByIdAndShopIdAndStatus(customerId, shopId, Customer.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!consents.findFirstByCustomerIdAndItemOrderByIdDesc(customerId, Consent.Item.PHOTO)
                .map(Consent::isAgreed)
                .orElse(false)) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED, "사진 동의가 필요합니다");
        }
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM photo WHERE visit_id = :visitId AND shop_id = :shopId",
                        p,
                        Integer.class);
        if (count != null && count >= MAX_PER_VISIT) {
            throw invalid("사진은 방문당 10장까지 올릴 수 있습니다");
        }

        String key = storage.save(new ByteArrayInputStream(file));
        deleteOnRollback(key);
        GeneratedKeyHolder id = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at)"
                        + " VALUES (:shopId, :visitId, :key, :now)",
                p.addValue("key", key).addValue("now", LocalDateTime.now(clock)),
                id,
                new String[] {"id"});
        accessLogger.record(
                FeatureKey.CUSTOMER_WRITE, AccessLogger.CUSTOMER, customerId, Action.UPDATE);
        return id.getKey().longValue();
    }

    /** F9 · F16: 커밋되지 않으면 이미 쓴 파일을 지운다. 실패해도 DB에는 행이 없으니 기록만 남긴다 */
    private void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            return;
                        }
                        try {
                            storage.delete(key);
                        } catch (IOException e) {
                            log.warn("롤백된 사진 파일 삭제 실패", e);
                        }
                    }
                });
    }

    private static boolean startsWith(byte[] file, byte[] magic) {
        return file.length >= magic.length
                && Arrays.equals(Arrays.copyOf(file, magic.length), magic);
    }

    private static ApiException invalid(String reason) {
        return new ApiException(
                ErrorCode.INVALID, reason, List.of(new FieldViolation("file", reason)));
    }
}
