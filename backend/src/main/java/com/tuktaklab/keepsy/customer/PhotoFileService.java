package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 서명 URL로 사진 파일을 열고, 사진을 지운다 (NFR-07, F16, CONTRACTS 6장 「고객 (CUS)」). 삭제는 다른 도메인(방문 삭제)도 이 공개 메서드를
 * 부른다 (DEVELOPMENT 4장 「도메인 연동」).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoFileService {

    public record PhotoFile(InputStream content, MediaType type) {}

    private final PhotoStorage storage;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** 방문 하나의 사진 (방문 삭제 = 마감 되돌리기, FR-VIS-03). 부르는 쪽 트랜잭션에서 함께 커밋된다 */
    @Transactional
    public void deleteVisitPhotos(long shopId, long visitId) {
        delete(" AND visit_id = :id", shopId, visitId);
    }

    /** 고객의 모든 방문 사진 (사진 동의 철회 FR-CUS-09, 고객 삭제 FR-CUS-10). 부르는 쪽 트랜잭션에서 함께 커밋된다 */
    @Transactional
    public void deleteCustomerPhotos(long shopId, long customerId) {
        delete(
                " AND visit_id IN (SELECT id FROM visit WHERE customer_id = :id)",
                shopId,
                customerId);
    }

    /** 행을 지우고 파일은 커밋 뒤에 지운다. 롤백되면 파일이 남아야 한다 (F16). 파일 삭제가 실패해도 DB는 이미 맞으니 기록만 남긴다 */
    private void delete(String condition, long shopId, long id) {
        String where = " FROM photo WHERE shop_id = :shopId" + condition;
        Map<String, Object> p = Map.of("shopId", shopId, "id", id);
        List<String> keys = jdbc.queryForList("SELECT storage_key" + where, p, String.class);
        jdbc.update("DELETE" + where, p);
        if (keys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (String key : keys) {
                            try {
                                storage.delete(key);
                            } catch (IOException e) {
                                log.warn("사진 파일 삭제 실패 shopId={}", shopId, e);
                            }
                        }
                    }
                });
    }

    /**
     * 서명 · 만료를 세션 매장으로 확인하고 그 매장의 사진만 연다. 호출하는 쪽이 스트림을 닫는다.
     *
     * @throws ApiException 서명이 틀리거나 만료됐거나, 다른 매장 · 없는 사진이거나 저장소에 파일이 없으면 모두 {@code not_found} (어느
     *     쪽인지 알려 주지 않는다)
     */
    public PhotoFile open(long shopId, long photoId, long expires, String signature)
            throws IOException {
        if (!storage.verify(photoId, shopId, expires, signature, clock.instant())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        List<String> keys =
                jdbc.queryForList(
                        "SELECT storage_key FROM photo WHERE id = :photoId AND shop_id = :shopId",
                        Map.of("photoId", photoId, "shopId", shopId),
                        String.class);
        if (keys.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        InputStream in;
        try {
            in = new BufferedInputStream(storage.open(keys.get(0)));
        } catch (NoSuchFileException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        // 파일 이름에 확장자가 없어 앞 바이트로 형식을 본다 (JPEG · PNG · GIF)
        String type = URLConnection.guessContentTypeFromStream(in);
        return new PhotoFile(
                in,
                type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type));
    }
}
