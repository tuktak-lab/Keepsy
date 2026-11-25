package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.customer.CustomerCsv.Parsed;
import com.tuktaklab.keepsy.customer.CustomerCsv.Row;
import com.tuktaklab.keepsy.customer.CustomerCsv.RowError;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** CSV 파일 읽기 · 행 검사 (FR-CUS-13, 결정 F50). 모의 이름 · 번호만 쓴다 */
class CustomerCsvTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final String HEADER = "이름,휴대폰,성별,출생연도,메모,방문일,시술카테고리\n";

    private static Parsed read(String csv) {
        return CustomerCsv.read(csv.getBytes(StandardCharsets.UTF_8), TODAY);
    }

    private static void assertInvalid(byte[] file, String reason) {
        assertThatThrownBy(() -> CustomerCsv.read(file, TODAY))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void 한글_값을_enum으로_바꾸고_번호를_맞춘다() {
        Parsed p = read(HEADER + "모의 고객,01000000001,여성,1990,첫 상담,2026-03-01,속눈썹\n");

        assertThat(p.errors()).isEmpty();
        assertThat(p.rows())
                .containsExactly(
                        new Row(
                                2,
                                "모의 고객",
                                "010-0000-0001",
                                Customer.Gender.FEMALE,
                                1990,
                                "첫 상담",
                                LocalDate.of(2026, 3, 1),
                                "LASH"));
    }

    @Test
    void BOM_UTF8과_CP949를_읽는다() {
        String csv = "이름,휴대폰\n모의 고객,010-0000-0001\n";
        byte[] utf8 = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[utf8.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, bom, 3, utf8.length);

        for (byte[] file : new byte[][] {bom, csv.getBytes(Charset.forName("MS949"))}) {
            assertThat(CustomerCsv.read(file, TODAY).rows())
                    .extracting(Row::name)
                    .containsExactly("모의 고객");
        }
    }

    @Test
    void 열_순서와_상관없고_선택_열은_빼도_된다() {
        Parsed p = read("휴대폰,메모,이름\n010-0000-0001,메모,모의 고객\n");

        assertThat(p.rows()).extracting(Row::name, Row::memo).containsExactly(tuple("모의 고객", "메모"));
    }

    @Test
    void 따옴표_안_쉼표_줄바꿈_큰따옴표를_받고_행_번호는_레코드_기준() {
        Parsed p =
                read(
                        "이름,휴대폰,메모\r\n"
                                + "모의 고객,010-0000-0001,\"첫째, 둘째\r\n셋째 \"\"인용\"\"\"\r\n"
                                + "모의 고객,010-0000-12\r\n");

        assertThat(p.rows()).extracting(Row::memo).containsExactly("첫째, 둘째\r\n셋째 \"인용\"");
        assertThat(p.errors()).extracting(RowError::row).containsExactly(3L);
    }

    @Test
    void 헤더가_틀리면_파일_단위_invalid() {
        assertInvalid("이름,메모\n모의,메모\n".getBytes(StandardCharsets.UTF_8), "이름 · 휴대폰 열");
        assertInvalid("이름,휴대폰,주소\n".getBytes(StandardCharsets.UTF_8), "알 수 없는 열");
        assertInvalid("이름,휴대폰,이름\n".getBytes(StandardCharsets.UTF_8), "같은 열");
        assertInvalid(new byte[0], "빈 파일");
        assertInvalid("이름,휴대폰\n".getBytes(StandardCharsets.UTF_8), "데이터 행이 없습니다");
        assertInvalid("이름,휴대폰\n\"모의,010-0000-0001\n".getBytes(StandardCharsets.UTF_8), "CSV 형식");
        // UTF-8도 CP949도 아닌 바이트
        assertInvalid(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, "UTF-8 또는 CP949");
    }

    @Test
    void 행_검사는_행마다_사유를_하나로_모은다() {
        Parsed p =
                read(
                        HEADER
                                + ",010-1234,남자,1899,,2026-02-30,\n"
                                + "모의,010-0000-0001,,2027,,2026-11-26,네일\n"
                                + "모의,010-0000-0002,,,,,손톱\n"
                                + "모의,010-0000-0003,,,,2026-03-01,\n"
                                + "모의,010-0000-0004,,,"
                                + "가".repeat(2_001)
                                + ",,\n"
                                + "가".repeat(51)
                                + ",010-0000-0005,,,   ,,\n");

        assertThat(p.rows()).isEmpty();
        assertThat(p.errors())
                .containsExactly(
                        new RowError(
                                2,
                                "이름이 없습니다, 휴대폰 번호는 010으로 시작하는 11자리입니다, 성별은 여성 · 남성 · 기타 중 하나입니다,"
                                        + " 출생연도는 1900년부터 올해까지의 4자리 숫자입니다, 방문일은 YYYY-MM-DD 형식입니다,"
                                        + " 방문일과 시술카테고리는 함께 적어 주세요"),
                        new RowError(3, "출생연도는 1900년부터 올해까지의 4자리 숫자입니다, 방문일이 오늘보다 늦습니다"),
                        new RowError(
                                4,
                                "시술카테고리는 네일 · 속눈썹 · 왁싱 · 반영구 · 기타 중 하나입니다, 방문일과 시술카테고리는 함께 적어 주세요"),
                        new RowError(5, "방문일과 시술카테고리는 함께 적어 주세요"),
                        new RowError(6, "메모는 2,000자 이하입니다"),
                        new RowError(7, "이름은 50자 이하입니다"));
    }

    @Test
    void 데이터는_3000행까지() {
        String row = "모의,010-0000-0001\n";
        assertThat(read("이름,휴대폰\n" + row.repeat(3_000)).rows()).hasSize(3_000);
        assertInvalid(("이름,휴대폰\n" + row.repeat(3_001)).getBytes(StandardCharsets.UTF_8), "3,000행");
    }

    @Test
    void 파일은_2MB까지() {
        // 헤더 + 메모 700자 행으로 정확히 2MB를 채운다 (3,000행 이하)
        String header = "이름,휴대폰,메모\n";
        // 헤더만 한글(UTF-8 3바이트), 나머지는 ASCII라 글자 수 = 바이트 수
        int target =
                CustomerCsv.MAX_BYTES
                        - (header.getBytes(StandardCharsets.UTF_8).length - header.length());
        StringBuilder csv = new StringBuilder(header);
        String row = "m,010-0000-0001," + "x".repeat(700) + "\n";
        while (csv.length() + 2 * row.length() < target) {
            csv.append(row);
        }
        String prefix = "m,010-0000-0001,";
        int memo = target - csv.length() - prefix.length() - 1;
        csv.append(prefix).append("x".repeat(memo)).append('\n');
        byte[] exact = csv.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(exact).hasSize(CustomerCsv.MAX_BYTES);

        assertThat(CustomerCsv.read(exact, TODAY).errors()).isEmpty();
        byte[] over = Arrays.copyOf(exact, exact.length + 1);
        over[over.length - 1] = '\n';
        assertInvalid(over, "2MB");
    }
}
