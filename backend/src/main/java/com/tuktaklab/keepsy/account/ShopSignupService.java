package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.port.ShopDefaults;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매장 가입 (FR-ACC-01): 매장 1개와 원장 스탭, 매장 기본 템플릿(FR-CMP-03)을 함께 만든다 */
@Service
@RequiredArgsConstructor
public class ShopSignupService {

    private final ShopRepository shopRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final ShopDefaults shopDefaults;

    /** 업종은 5개 모두 켠다 (업종을 고르지 않는 호출: 테스트 · 시드). 자기 호출은 프록시를 거치지 않아 여기에도 트랜잭션을 건다 */
    @Transactional
    public long signUp(
            String email, String password, String shopName, String ownerName, String ownerPin) {
        return signUp(email, password, shopName, ownerName, ownerPin, Shop.CATEGORIES);
    }

    /**
     * @param industries 켜 둘 매장 업종 (CONTRACTS 3절 「매장 업종」)
     * @return 만든 매장 ID
     * @throws ApiException 이메일이 이미 있으면 {@code duplicate} (F11), 업종이 규칙을 어기면 {@code invalid}
     */
    @Transactional
    public long signUp(
            String email,
            String password,
            String shopName,
            String ownerName,
            String ownerPin,
            List<String> industries) {
        if (shopRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }
        Shop shop;
        try {
            shop =
                    shopRepository.saveAndFlush(
                            Shop.create(
                                    email, passwordEncoder.encode(password), shopName, industries));
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일로 동시에 가입하면 고유 제약(uk_shop_email)이 하나만 남긴다
            throw duplicateEmail();
        }
        staffRepository.save(
                Staff.owner(shop.getId(), ownerName, passwordEncoder.encode(ownerPin)));
        shopDefaults.create(shop.getId());
        return shop.getId();
    }

    private static ApiException duplicateEmail() {
        return new ApiException(ErrorCode.DUPLICATE, "이미 가입한 이메일입니다");
    }
}
