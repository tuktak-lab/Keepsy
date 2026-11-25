package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * CSV 고객 일괄 등록 파일 읽기와 행 검사 (FR-CUS-13, 결정 F50, CONTRACTS 6장 「`POST /api/customers/import`」). DB 없이
 * 단위 테스트한다.
 */
final class CustomerCsv {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_ROWS = 3_000;

    static final String NAME = "이름";
    static final String PHONE = "휴대폰";
    static final String GENDER = "성별";
    static final String BIRTH_YEAR = "출생연도";
    static final String MEMO = "메모";
    static final String VISIT_DATE = "방문일";
    static final String CATEGORY = "시술카테고리";
    private static final Set<String> HEADERS =
            Set.of(NAME, PHONE, GENDER, BIRTH_YEAR, MEMO, VISIT_DATE, CATEGORY);

    private static final Map<String, Customer.Gender> GENDERS =
            Map.of(
                    "여성",
                    Customer.Gender.FEMALE,
                    "남성",
                    Customer.Gender.MALE,
                    "기타",
                    Customer.Gender.OTHER);
    private static final Map<String, String> CATEGORIES =
            Map.of(
                    "네일", "NAIL",
                    "속눈썹", "LASH",
                    "왁싱", "WAXING",
                    "반영구", "SEMI_PERMANENT",
                    "기타", "ETC");

    /** 고객 등록 API와 같은 형식 */
    private static final Pattern PHONE_FORMAT = Pattern.compile("010-?\\d{4}-?\\d{4}");

    private static final Charset CP949 = Charset.forName("MS949");

    private CustomerCsv() {}

    /** 검사를 통과한 행. 휴대폰은 {@code 010-0000-0000}, 카테고리는 3절 enum 이름 */
    record Row(
            long row,
            String name,
            String phone,
            Customer.Gender gender,
            Integer birthYear,
            String memo,
            LocalDate visitDate,
            String category) {}

    record RowError(long row, String reason) {}

    record Parsed(List<Row> rows, List<RowError> errors) {}

    /**
     * 파일 단위 검사(크기 · 인코딩 · 헤더 · 행 수)를 하고 행마다 값을 검사한다.
     *
     * @param today 오늘 (Asia/Seoul). 방문일 · 출생연도 상한
     * @throws ApiException 파일 단위 문제면 {@code invalid}. 아무것도 저장하지 않는다
     */
    static Parsed read(byte[] file, LocalDate today) {
        if (file == null || file.length == 0) {
            throw invalid("빈 파일입니다");
        }
        if (file.length > MAX_BYTES) {
            throw invalid("파일은 2MB까지 올릴 수 있습니다");
        }
        List<CSVRecord> records;
        try (CSVParser parser = CSVParser.parse(decode(file), CSVFormat.DEFAULT)) {
            records = parser.getRecords();
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            throw invalid("CSV 형식을 읽을 수 없습니다. 따옴표가 닫혔는지 확인해 주세요");
        }
        if (records.isEmpty()) {
            throw invalid("빈 파일입니다");
        }
        Map<String, Integer> columns = header(records.getFirst());
        int dataRows = records.size() - 1;
        if (dataRows == 0) {
            throw invalid("데이터 행이 없습니다");
        }
        if (dataRows > MAX_ROWS) {
            throw invalid("데이터는 3,000행까지 올릴 수 있습니다");
        }
        List<Row> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (CSVRecord r : records.subList(1, records.size())) {
            List<String> problems = new ArrayList<>();
            Row row = row(r, columns, today, problems);
            if (problems.isEmpty()) {
                rows.add(row);
            } else {
                errors.add(new RowError(r.getRecordNumber(), String.join(", ", problems)));
            }
        }
        return new Parsed(rows, errors);
    }

    /** UTF-8(BOM 허용)로 읽고, 안 되면 CP949(엑셀 기본 CSV)로 읽는다 */
    static String decode(byte[] file) {
        int bom =
                file.length >= 3
                                && file[0] == (byte) 0xEF
                                && file[1] == (byte) 0xBB
                                && file[2] == (byte) 0xBF
                        ? 3
                        : 0;
        ByteBuffer bytes = ByteBuffer.wrap(file, bom, file.length - bom);
        for (Charset charset : List.of(StandardCharsets.UTF_8, CP949)) {
            try {
                return charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(bytes.duplicate())
                        .toString();
            } catch (CharacterCodingException e) {
                // 다음 인코딩으로
            }
        }
        throw invalid("UTF-8 또는 CP949(엑셀 CSV)로 저장한 파일만 읽을 수 있습니다");
    }

    private static Map<String, Integer> header(CSVRecord first) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < first.size(); i++) {
            String name = first.get(i).strip();
            if (!HEADERS.contains(name)) {
                throw invalid("알 수 없는 열입니다: " + name);
            }
            if (columns.put(name, i) != null) {
                throw invalid("같은 열이 두 번 있습니다: " + name);
            }
        }
        if (!columns.containsKey(NAME) || !columns.containsKey(PHONE)) {
            throw invalid("이름 · 휴대폰 열은 꼭 있어야 합니다");
        }
        return columns;
    }

    private static Row row(
            CSVRecord r, Map<String, Integer> columns, LocalDate today, List<String> problems) {
        String name = value(r, columns, NAME);
        if (name == null) {
            problems.add("이름이 없습니다");
        } else if (name.length() > 50) {
            problems.add("이름은 50자 이하입니다");
        }
        String phone = value(r, columns, PHONE);
        if (phone == null || !PHONE_FORMAT.matcher(phone).matches()) {
            problems.add("휴대폰 번호는 010으로 시작하는 11자리입니다");
            phone = null;
        }
        String genderText = value(r, columns, GENDER);
        Customer.Gender gender = genderText == null ? null : GENDERS.get(genderText);
        if (genderText != null && gender == null) {
            problems.add("성별은 여성 · 남성 · 기타 중 하나입니다");
        }
        Integer birthYear = birthYear(value(r, columns, BIRTH_YEAR), today, problems);
        String memo = value(r, columns, MEMO);
        if (memo != null && memo.length() > 2_000) {
            problems.add("메모는 2,000자 이하입니다");
        }
        LocalDate visitDate = visitDate(value(r, columns, VISIT_DATE), today, problems);
        String categoryText = value(r, columns, CATEGORY);
        String category = categoryText == null ? null : CATEGORIES.get(categoryText);
        if (categoryText != null && category == null) {
            problems.add("시술카테고리는 네일 · 속눈썹 · 왁싱 · 반영구 · 기타 중 하나입니다");
        }
        if ((value(r, columns, VISIT_DATE) == null) != (categoryText == null)) {
            problems.add("방문일과 시술카테고리는 함께 적어 주세요");
        }
        return new Row(
                r.getRecordNumber(),
                name,
                phone == null ? null : Customer.normalizePhone(phone),
                gender,
                birthYear,
                memo,
                visitDate,
                category);
    }

    private static Integer birthYear(String text, LocalDate today, List<String> problems) {
        if (text == null) {
            return null;
        }
        if (!text.matches("\\d{4}")
                || Integer.parseInt(text) < 1900
                || Integer.parseInt(text) > today.getYear()) {
            problems.add("출생연도는 1900년부터 올해까지의 4자리 숫자입니다");
            return null;
        }
        return Integer.parseInt(text);
    }

    private static LocalDate visitDate(String text, LocalDate today, List<String> problems) {
        if (text == null) {
            return null;
        }
        try {
            // ISO_LOCAL_DATE는 STRICT라 2026-02-30 같은 날짜를 받지 않는다
            LocalDate date = LocalDate.parse(text);
            if (date.isAfter(today)) {
                problems.add("방문일이 오늘보다 늦습니다");
                return null;
            }
            return date;
        } catch (DateTimeParseException e) {
            problems.add("방문일은 YYYY-MM-DD 형식입니다");
            return null;
        }
    }

    /** 앞뒤 공백을 뗀 값. 없는 열 · 빈 값이면 null */
    private static String value(CSVRecord r, Map<String, Integer> columns, String column) {
        Integer i = columns.get(column);
        if (i == null || i >= r.size()) {
            return null;
        }
        String v = r.get(i).strip();
        return v.isEmpty() ? null : v;
    }

    private static ApiException invalid(String reason) {
        return new ApiException(ErrorCode.INVALID, reason);
    }
}
