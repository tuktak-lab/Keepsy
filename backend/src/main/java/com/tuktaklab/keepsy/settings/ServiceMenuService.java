package com.tuktaklab.keepsy.settings;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시술 메뉴 (FR-SET-02). 매장이 직접 등록하는 작은 목록이라 페이징하지 않는다 (F22) */
@Service
@RequiredArgsConstructor
public class ServiceMenuService {

    private final ServiceMenuRepository menuRepository;

    @Transactional(readOnly = true)
    public List<ServiceMenu> menus(long shopId) {
        return menuRepository.findAllInDisplayOrder(shopId);
    }
}
