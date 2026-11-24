package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 사진 업로드 · 파일 (CONTRACTS 4 · 6장 「고객 (CUS)」). 파일 읽기는 기능 키 대신 매장 로그인 + 서명 · 만료로 막는다. URL은 사진을 보여 주는
 * API(히스토리북 등)가 권한 · 사진 동의를 확인한 뒤 발급한다.
 */
@RestController
@RequiredArgsConstructor
class PhotoFileController {

    private final PhotoFileService service;
    private final PhotoUploadService uploadService;
    private final ShopSession shopSession;

    record UploadResponse(long id) {}

    /** 요청 하나에 한 장 (FR-CUS-08) */
    @RequireFeature(FeatureKey.CUSTOMER_WRITE)
    @PostMapping(
            value = "/api/visits/{visitId}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse upload(@PathVariable long visitId, @RequestPart("file") MultipartFile file)
            throws IOException {
        return new UploadResponse(
                uploadService.upload(shopSession.shopId(), visitId, file.getBytes()));
    }

    @GetMapping("/api/photos/{photoId}/file")
    ResponseEntity<InputStreamResource> file(
            @PathVariable long photoId, @RequestParam long expires, @RequestParam String signature)
            throws IOException {
        PhotoFileService.PhotoFile file =
                service.open(shopSession.shopId(), photoId, expires, signature);
        return ResponseEntity.ok()
                .contentType(file.type())
                .body(new InputStreamResource(file.content()));
    }
}
