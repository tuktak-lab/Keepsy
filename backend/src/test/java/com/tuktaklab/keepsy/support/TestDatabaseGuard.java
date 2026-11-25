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
 * </ul>
 */
public class TestDatabaseGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String host = env.getProperty("MYSQL_TEST_HOST", "127.0.0.1");
        int port = Integer.parseInt(env.getProperty("MYSQL_TEST_PORT", "3307"));
        String database = env.getProperty("MYSQL_TEST_DATABASE", "keepsy_test");
        if (!database.endsWith("_test")) {
            throw new IllegalStateException("테스트 DB 이름은 _test로 끝나야 합니다 (개발 DB 보호): " + database);
        }
        requireReachable(host, port);

        // compose mysql-test · CI 컨테이너 안에서만 쓰는 임시 값이다 (실제 비밀 값이 아님)
        env.getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "keepsyTestDatabase",
                                Map.of(
                                        "spring.datasource.url",
                                        "jdbc:mysql://%s:%d/%s?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
                                                .formatted(host, port, database),
                                        "spring.datasource.username",
                                        env.getProperty("MYSQL_TEST_USER", "keepsy_test"),
                                        "spring.datasource.password",
                                        env.getProperty("MYSQL_TEST_PASSWORD", "keepsy-test"),
                                        "APP_STORAGE_DIR",
                                        "build/test-photos",
                                        "APP_STORAGE_SIGNING_KEY",
                                        "keepsy-local-test-signing-key-not-a-secret")));
    }

    private static void requireReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "테스트 DB(%s:%d)에 연결할 수 없습니다. 저장소 루트에서 `docker compose --profile test up -d --wait mysql-test`로 띄워 주세요"
                            .formatted(host, port),
                    e);
        }
    }

    /** {@code .env}(config data)를 읽은 뒤에 실행해 그 값보다 앞에 둔다 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
