package com.tuktaklab.keepsy.settings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ServiceMenuRepository extends JpaRepository<ServiceMenu, Long> {

    /** 매장 메뉴 전부. 카테고리는 CONTRACTS 3장 순서, 그 안에서 등록 순 (CONTRACTS 6장 GET /api/service-menus) */
    @Query(
            "SELECT m FROM ServiceMenu m WHERE m.shopId = :shopId ORDER BY CASE m.category"
                    + " WHEN 'NAIL' THEN 0 WHEN 'LASH' THEN 1 WHEN 'WAXING' THEN 2"
                    + " WHEN 'SEMI_PERMANENT' THEN 3 ELSE 4 END, m.id")
    List<ServiceMenu> findAllInDisplayOrder(@Param("shopId") long shopId);

    Optional<ServiceMenu> findByIdAndShopId(Long id, Long shopId);
}
