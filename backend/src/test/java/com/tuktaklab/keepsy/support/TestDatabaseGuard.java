package com.tuktaklab.keepsy.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 테스트가 개발 DB · 개발 사진 디렉터리를 쓰지 않게 막는다 (Keepsy #330). 테스트 클래스패스에만 있다 ({@code
 * META-INF/spring.factories}).
 *
 * <p>일부 테스트가 모든 매장의 발송 대기 · 접근 로그를 바꾸므로, DB 주소와 사진 경로를 OS 환경 변수 · {@code .env}보다 앞선 속성으로 고정한다. DB
 * 이름이 {@code _test}로 끝나지 않거나 테스트 DB에 연결할 수 없으면 컨텍스트를 띄우기 전에 실패한다.
 *
 * <ul>
 *   <li>로컬: 저장소 루트에서 {@code docker compose --profile test up -d --wait mysql-test} (127.0.0.1:3307)
 *   <li>CI: 워크플로의 {@code MYSQL_TEST_*} 환경 변수가 서비스 컨테이너를 가리킨다
 *   <li>시드 생성 {@code ./gradlew seedDemo}(#335): 데모 DB {@code docker compose --profile demo up -d
 *       --wait mysql-demo} (127.0.0.1:3308, 이름이 {@code _demo}로 끝나야 함, {@code MYSQL_DEMO_*})
 * </ul>
 */
public class TestDatabaseGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        // seedDemo 태스크(#335)는 테스트 DB 대신 데모 DB(compose mysql-demo)에 붙는다. 이름 규칙은 같은 방식으로 _demo
        boolean demo = "demo".equals(env.getProperty("keepsy.seed.target"));
        String kind = demo ? "DEMO" : "TEST";
        String suffix = demo ? "_demo" : "_test";
        String host = env.getProperty("MYSQL_" + kind + "_HOST", "127.0.0.1");
        int port =
                Integer.parseInt(
                        env.getProperty("MYSQL_" + kind + "_PORT", demo ? "3308" : "3307"));
        String database = env.getProperty("MYSQL_" + kind + "_DATABASE", "keepsy" + suffix);
        if (!database.endsWith(suffix)) {
            throw new IllegalStateException(
                    "%s DB 이름은 %s로 끝나야 합니다 (개발 DB 보호): %s"
                            .formatted(demo ? "데모" : "테스트", suffix, database));
        }
        requireReachable(host, port, demo ? "demo" : "test");

        // compose mysql-test · mysql-demo · CI 컨테이너 안에서만 쓰는 임시 값이다 (실제 비밀 값이 아님)
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "keepsyTestDatabase",
                                Map.of(
                                        "spring.datasource.url",
                                        "jdbc:mysql://%s:%d/%s?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
                                                .formatted(host, port, database),
                                        "spring.datasource.username",
                                        env.getProperty(
                                                "MYSQL_" + kind + "_USER", "keepsy" + suffix),
                                        "spring.datasource.password",
                                        env.getProperty(
                                                "MYSQL_" + kind + "_PASSWORD",
                                                demo ? "keepsy-demo" : "keepsy-test"),
                                        "APP_STORAGE_DIR",
                                        "build/test-photos",
                                        "APP_STORAGE_SIGNING_KEY",
                                        "keepsy-local-test-signing-key-not-a-secret")));
    }

    private static void requireReachable(String host, int port, String profile) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "%s DB(%s:%d)에 연결할 수 없습니다. 저장소 루트에서 `docker compose --profile %s up -d --wait mysql-%s`로 띄워 주세요"
                            .formatted(profile, host, port, profile, profile),
                    e);
        }
    }

    /** {@code .env}(config data)를 읽은 뒤에 실행해 그 값보다 앞에 둔다 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
