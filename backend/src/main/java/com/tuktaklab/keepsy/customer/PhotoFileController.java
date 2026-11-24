package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.security.ShopSession;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사진 파일 (CONTRACTS 4장 「고객 (CUS)」, 권한 "서명 URL"). 기능 키 대신 매장 로그인 + 서명 · 만료로 막는다. URL은 사진을 보여 주는
 * API(히스토리북 등)가 권한 · 사진 동의를 확인한 뒤 발급한다.
 */
@RestController
@RequiredArgsConstructor
class PhotoFileController {

    private final PhotoFileService service;
    private final ShopSession shopSession;

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
