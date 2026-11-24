package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매장 가입 (FR-ACC-01): 매장 1개와 원장 스탭을 함께 만든다 */
@Service
@RequiredArgsConstructor
public class ShopSignupService {

    private final ShopRepository shopRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * @return 만든 매장 ID
     * @throws ApiException 이메일이 이미 있으면 {@code duplicate} (F11)
     */
    @Transactional
    public long signUp(
            String email, String password, String shopName, String ownerName, String ownerPin) {
        if (shopRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }
        Shop shop;
        try {
            shop =
                    shopRepository.saveAndFlush(
                            Shop.create(email, passwordEncoder.encode(password), shopName));
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일로 동시에 가입하면 고유 제약(uk_shop_email)이 하나만 남긴다
            throw duplicateEmail();
        }
        staffRepository.save(
                Staff.owner(shop.getId(), ownerName, passwordEncoder.encode(ownerPin)));
        return shop.getId();
    }

    private static ApiException duplicateEmail() {
        return new ApiException(ErrorCode.DUPLICATE, "이미 가입한 이메일입니다");
    }
}
