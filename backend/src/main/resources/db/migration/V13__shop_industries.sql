-- V13 매장 업종 (Keepsy #421, FR-ACC-01 · FR-SET-01, CONTRACTS 3절 「매장 업종」 [제안])
-- 가입 때 고르고 매장 설정에서 켜고 끄는 업종. 값은 시술 카테고리 5개와 같다 (CONTRACTS 3절 순서).
-- 끄면 시술 메뉴 · 기본 주기 설정 화면에서만 숨기고 기존 메뉴 · 방문 · 예측 · 캠페인 데이터는 그대로 둔다.
-- 쉼표로 이은 문자열은 pin_locked_features와 같은 방식이다. 기존 매장은 DEFAULT로 5개 모두 켠 채로 시작한다.
ALTER TABLE shop
    ADD COLUMN industries VARCHAR(100) NOT NULL DEFAULT 'NAIL,LASH,WAXING,SEMI_PERMANENT,ETC'
        COMMENT '켜 둔 매장 업종, 쉼표로 이음. NAIL | LASH | WAXING | SEMI_PERMANENT | ETC 중 1개 이상 (FR-SET-01)';
