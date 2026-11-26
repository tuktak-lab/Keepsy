-- V12 perf 시드에서 효과가 확인된 인덱스 (Keepsy #350, NFR-01, C17, ARCHITECTURE 8장)
-- 측정: 매장 1 · 고객 3,000 · 방문 19,061 · 예약 20,445 (seedDemo perf), MySQL 8.4.13, EXPLAIN ANALYZE 5회

-- 대시보드 「마감 안 된 예약」(DashboardTodayQuery): 지난 확정 예약만 고른다. 없으면 지난 예약 전체를 읽고 status로 걸렀다
-- (19,901행 읽고 0행, 15.5ms → 0행, 0.02ms)
CREATE INDEX idx_reservation_shop_status_start ON reservation (shop_id, status, start_at);

-- 매장 요약 신규 고객 수(ShopSummaryQuery): 고객별 첫 방문일을 인덱스만으로 구한다. 없으면 매장 방문 전체를 임시 테이블로 묶었다
-- (18.6ms → 5.6ms)
CREATE INDEX idx_visit_shop_customer_date ON visit (shop_id, customer_id, visit_date);
