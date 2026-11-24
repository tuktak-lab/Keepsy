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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 서명 URL로 사진 파일을 연다 (NFR-07, F16, CONTRACTS 6장 「고객 (CUS)」) */
@Service
@RequiredArgsConstructor
public class PhotoFileService {

    public record PhotoFile(InputStream content, MediaType type) {}

    private final PhotoStorage storage;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

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
