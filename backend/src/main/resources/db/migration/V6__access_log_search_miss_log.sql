-- V6 접근 로그 · 미매칭 검색어 로그 (Keepsy #11)
-- 기준: 요구사항명세서 5장 access_log · search_miss_log, NFR-06, FR-SCH-06.

-- 개인정보 조회 · 수정 · 삭제 · 다운로드 이력. 2년 보관 (D2). 고객을 지워도 남는다 (C12),
-- 그래서 대상에는 외래 키를 걸지 않는다. 백업 복구 뒤 다시 지울 고객도 여기서 찾는다 (NFR-10, C12)
CREATE TABLE access_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT      NOT NULL,
    staff_id    BIGINT      NOT NULL COMMENT '잠긴 기능이면 PIN 인증 스탭, 공용 기능이면 현재 근무자 (ARCHITECTURE 6장)',
    target_type VARCHAR(30) NOT NULL COMMENT '대상 유형 (예: CUSTOMER)',
    target_id   BIGINT      NOT NULL,
    action      VARCHAR(20) NOT NULL COMMENT 'VIEW | UPDATE | DELETE | DOWNLOAD (NFR-06 조회 · 수정 · 삭제 · 다운로드)',
    accessed_at DATETIME    NOT NULL,
    ip          VARCHAR(45) NOT NULL COMMENT 'IPv6까지',
    PRIMARY KEY (id),
    -- 기간 조회 · 2년 지난 행 정리 · 복구 시점 이후 삭제 찾기
    KEY idx_access_log_shop_accessed (shop_id, accessed_at),
    CONSTRAINT fk_access_log_shop FOREIGN KEY (shop_id) REFERENCES shop (id),
    CONSTRAINT fk_access_log_staff FOREIGN KEY (staff_id) REFERENCES staff (id)
);

-- 결과 없는 검색어. 사전 보강에 쓴다 (FR-SCH-06, P2)
CREATE TABLE search_miss_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    shop_id     BIGINT       NOT NULL,
    keyword     VARCHAR(100) NOT NULL,
    searched_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_search_miss_log_shop_searched (shop_id, searched_at),
    CONSTRAINT fk_search_miss_log_shop FOREIGN KEY (shop_id) REFERENCES shop (id)
);
