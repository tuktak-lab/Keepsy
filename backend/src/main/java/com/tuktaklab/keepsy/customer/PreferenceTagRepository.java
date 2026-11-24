package com.tuktaklab.keepsy.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PreferenceTagRepository extends JpaRepository<PreferenceTag, Long> {

    /** 바로 실행한다. 같은 태그를 다시 넣을 때 고유 제약(uk_preference_tag_customer)에 걸리지 않게 새 행보다 먼저 지운다 */
    @Modifying
    @Query("delete from PreferenceTag t where t.shopId = :shopId and t.customerId = :customerId")
    void deleteCustomerTags(long shopId, long customerId);

    /** 매장에서 쓴 태그 (F3). 카테고리 안에서 중복 없이 이름 순 */
    @Query(
            "select distinct t.tag from PreferenceTag t"
                    + " where t.shopId = :shopId and t.category = :category order by t.tag")
    List<String> findShopTags(long shopId, String category);
}
