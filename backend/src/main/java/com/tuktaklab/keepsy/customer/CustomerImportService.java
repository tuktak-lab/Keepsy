package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler.FieldViolation;
import com.tuktaklab.keepsy.customer.CustomerCsv.Row;
import com.tuktaklab.keepsy.customer.port.CustomerImportReferences;
import com.tuktaklab.keepsy.customer.port.CustomerImportReferences.ImportedVisit;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV 고객 일괄 등록 (FR-CUS-13, B4, 결정 F4 · F5 · F50). 동기 처리, 한 트랜잭션이라 서버 오류면 파일 전체가 롤백된다. 행 검사에 걸린 행은
 * 아무것도 만들지 않고 건너뛴다.
 */
@Service
@RequiredArgsConstructor
public class CustomerImportService {

    private final CustomerRepository customerRepository;
    private final ConsentRepository consentRepository;
    private final ConsultNoteRepository noteRepository;
    private final CustomerImportReferences refs;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public record RowError(long row, String reason) {}

    /** CONTRACTS 6장 「`POST /api/customers/import` 응답 필드」 */
    public record Result(
            int createdCustomers,
            int addedVisits,
            int addedNotes,
            int skippedDuplicates,
            int errorCount,
            List<RowError> errors) {}

    /**
     * @param staffId 업로드한 현재 근무자. 상담 메모 작성자 · 동의 처리자 (F5)
     * @param consentConfirmed "파일의 고객은 매장이 개인정보 수집·이용 동의를 이미 받았습니다" 확인
     * @throws ApiException 확인 없음 · 파일 단위 문제면 {@code invalid} (아무것도 저장하지 않음)
     */
    @Transactional
    public Result importCsv(long shopId, long staffId, byte[] file, Boolean consentConfirmed) {
        if (!Boolean.TRUE.equals(consentConfirmed)) {
            String reason = "파일의 고객에게 개인정보 수집 · 이용 동의를 받았는지 확인해 주세요";
            throw new ApiException(
                    ErrorCode.INVALID,
                    reason,
                    List.of(new FieldViolation("consentConfirmed", reason)));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        CustomerCsv.Parsed parsed = CustomerCsv.read(file, LocalDate.now(clock));
        List<Row> rows = parsed.rows();
        List<RowError> errors =
                parsed.errors().stream().map(e -> new RowError(e.row(), e.reason())).toList();
        if (rows.isEmpty()) {
            return new Result(0, 0, 0, 0, errors.size(), errors);
        }

        // 매장 안 같은 번호의 정상 고객. 삭제된 고객은 번호가 없어 새 고객이 된다 (C12)
        Map<String, Long> byPhone = new HashMap<>();
        customerRepository
                .findByShopIdAndStatusAndPhoneIn(
                        shopId,
                        Customer.Status.ACTIVE,
                        rows.stream().map(Row::phone).distinct().toList())
                .forEach(c -> byPhone.put(c.getPhone(), c.getId()));
        Set<Integer> touched = new HashSet<>(); // 무언가 더한 행 (rows 인덱스)
        int created = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (byPhone.containsKey(r.phone())) {
                continue; // 기존 고객: 기본 정보 · 동의는 바꾸지 않는다
            }
            Customer customer =
                    Customer.register(
                            shopId,
                            new NewCustomer(
                                    r.name(),
                                    r.phone(),
                                    Set.of(Consent.Item.PRIVACY),
                                    Consent.Method.IMPORT,
                                    r.gender(),
                                    r.birthYear(),
                                    null,
                                    null),
                            now);
            try {
                customerRepository.save(customer); // IDENTITY라 여기서 INSERT가 나간다
            } catch (DataIntegrityViolationException e) {
                // 위에서 번호를 확인한 뒤 다른 요청(고객 등록 · 다른 가져오기)이 같은 번호를 먼저 커밋했다. 예상할 수 있는 업무 중복만 duplicate로
                // 바꾸고 나머지 제약 위반은 그대로 500이다 (F63). 파일 전체를 되돌린다
                if (isPhoneDuplicate(e)) {
                    throw new ApiException(ErrorCode.DUPLICATE, "같은 번호가 방금 등록되었습니다. 파일을 다시 올려 주세요");
                }
                throw e;
            }
            // 마케팅 동의는 행을 만들지 않아 "받은 적 없음"(미동의)이다 (F19)
            consentRepository.save(
                    Consent.agreed(
                            customer, Consent.Item.PRIVACY, Consent.Method.IMPORT, staffId, now));
            byPhone.put(r.phone(), customer.getId());
            touched.add(i);
            created++;
        }

        int notes = addNotes(shopId, staffId, rows, byPhone, touched, now);

        List<Integer> visitRows = new ArrayList<>();
        List<ImportedVisit> visits = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.visitDate() != null) {
                visitRows.add(i);
                visits.add(new ImportedVisit(byPhone.get(r.phone()), r.visitDate(), r.category()));
            }
        }
        List<Boolean> added = refs.importVisits(shopId, visits);
        int addedVisits = 0;
        for (int k = 0; k < added.size(); k++) {
            if (added.get(k)) {
                touched.add(visitRows.get(k));
                addedVisits++;
            }
        }

        return new Result(
                created, addedVisits, notes, rows.size() - touched.size(), errors.size(), errors);
    }

    /** 매장 안 번호 고유 제약(uk_customer_shop_phone) 위반인지. MySQL은 제약 이름 앞에 테이블 이름을 붙여 알려 준다 */
    private static boolean isPhoneDuplicate(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cv
                && cv.getConstraintName() != null
                && cv.getConstraintName().endsWith("uk_customer_shop_phone");
    }

    /** 같은 고객에 내용이 완전히 같은 상담 메모가 없을 때만 만든다 (F5). 파일 안 반복 행 · 같은 파일 재업로드도 늘지 않는다 */
    private int addNotes(
            long shopId,
            long staffId,
            List<Row> rows,
            Map<String, Long> byPhone,
            Set<Integer> touched,
            LocalDateTime now) {
        List<Long> customerIds =
                rows.stream()
                        .filter(r -> r.memo() != null)
                        .map(r -> byPhone.get(r.phone()))
                        .distinct()
                        .toList();
        if (customerIds.isEmpty()) {
            return 0;
        }
        Set<String> existing = new HashSet<>();
        jdbc.query(
                "SELECT customer_id, content FROM consult_note"
                        + " WHERE shop_id = :shopId AND customer_id IN (:ids)",
                Map.of("shopId", shopId, "ids", customerIds),
                rs -> {
                    existing.add(rs.getLong("customer_id") + "\n" + rs.getString("content"));
                });
        List<ConsultNote> notes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            long customerId = byPhone.get(r.phone());
            if (r.memo() != null && existing.add(customerId + "\n" + r.memo())) {
                notes.add(ConsultNote.write(shopId, customerId, staffId, r.memo(), now));
                touched.add(i);
            }
        }
        noteRepository.saveAll(notes);
        return notes.size();
    }
}
