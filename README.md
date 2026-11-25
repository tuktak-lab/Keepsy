# Keepsy (킵시)

뷰티샵 CRM Keepsy의 구현 저장소입니다 (뚝딱랩). 기획 · 결정 · 설계 문서는 [tuktak-lab/docs](https://github.com/tuktak-lab/docs)에 있고, 이 저장소에는 두지 않습니다.

- 범위: 모의 데이터로 개발 · 시연까지. 실제 문자 발송 없이 가상 발송기만 쓰고, 배포 없이 로컬에서 실행합니다.

## 구성

```text
backend/        Spring Boot 4.2.0 · Java 21 · Gradle (패키지 com.tuktaklab.keepsy)
frontend/       Next.js 16 정적 빌드 · React 19 · Node 24 (데모 Keepsy-design@6ac161c에서 전환, #119). 화면만 있고 API 연동 전
compose.yaml    로컬 MySQL 8.4
.env.example    환경 변수 이름. 복사해서 .env를 만들고 값을 채웁니다 (.env는 커밋 금지)
```

## 로컬 실행

docs 저장소를 이 저장소와 같은 상위 폴더에 `docs`라는 이름으로 클론해 두면(`../docs`) 문서 경로가 그대로 맞습니다.

1. `.env.example`을 복사해 `.env`를 만들고 값을 채웁니다.
2. 실행 명령과 순서는 docs 저장소 [`docs/DEVELOPMENT.md` 6장 「실행 명령」](https://github.com/tuktak-lab/docs/blob/main/docs/DEVELOPMENT.md)을 따릅니다. "확인됨"으로 표시된 명령만 검증된 것입니다.
3. 백엔드 테스트(`./gradlew test` · `build`)는 개발 DB가 아니라 테스트 전용 DB(`127.0.0.1:3307`, 데이터는 메모리)에 붙습니다. 먼저 저장소 루트에서 `docker compose --profile test up -d --wait mysql-test`로 띄웁니다. 테스트 DB가 없거나 DB 이름이 `_test`로 끝나지 않으면 테스트가 바로 실패합니다. 백엔드 · MySQL · 프론트 개발 서버는 이 PC(`127.0.0.1`)에서만 열립니다 (결정 F64).
4. 시연 · 성능 점검용 시드(C17)는 개발 DB가 아니라 데모 전용 DB(`127.0.0.1:3308`, `keepsy_demo`)에 넣습니다. `docker compose --profile demo up -d --wait mysql-demo` 후 `backend/`에서 `./gradlew seedDemo -Pseed.scale=perf -Pseed.until=<시연일>`(규모 `small` · `demo` · `perf`, 고정 난수 `-Pseed.random=…`, 다시 만들기 `-Pseed.recreate=true`). 백엔드를 데모 DB로 띄우려면 `MYSQL_PORT=3308 MYSQL_DATABASE=keepsy_demo MYSQL_USER=keepsy_demo MYSQL_PASSWORD=keepsy-demo ./gradlew bootRun` (로컬 시연 컨테이너용 임시 값). 데모 매장 로그인은 `demo@example.com` / `keepsy-demo-1234`, 원장 PIN `1111` (모의 데이터).

## 협업

- 이슈 · PR 템플릿, 라벨, 리뷰 담당(CODEOWNERS): [.github/](./.github/)
- CodeRabbit은 자동 리뷰를 꺼 두었습니다. 필요할 때 PR 댓글에 `@coderabbitai review`를 적어 부릅니다 ([.coderabbit.yaml](./.coderabbit.yaml)).
- Claude Code로 작업할 때의 원칙: [CLAUDE.md](./CLAUDE.md)
