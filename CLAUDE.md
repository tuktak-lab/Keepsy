@README.md

# Claude 작업 원칙

이 저장소는 Keepsy **구현 저장소**다. 일반 규칙은 `.claude/rules/`(git-workflow · scope-control · security · verification)가 자동으로 적용되고, 아래는 이 저장소에서 작업할 때의 원칙이다.

## 문서 위치와 권위

- 기획 · 결정 · 설계 문서는 docs 저장소에 있다. 로컬 경로는 `../docs` (GitHub: https://github.com/tuktak-lab/docs). 없으면 사용자에게 위치를 묻는다.
- 먼저 읽을 것: `../docs/CLAUDE.md`, `../docs/README.md` → `../docs/docs/ARCHITECTURE.md`(2·3·4장) → `../docs/docs/DEVELOPMENT.md`(2~7장) → `../docs/docs/CONTRACTS.md`
- 권위 순서: `결정체크리스트.md` > `요구사항명세서.md` > `기획서.md` > `docs/*.md`. 문서끼리 어긋나거나 문서에 없는 결정이 필요하면 임의로 정하지 말고 사용자에게 묻는다. 문서 수정은 docs 저장소에서 위 순서대로 한다. 이 저장소에 문서를 복사해 두지 않는다.
- `[제안]`은 확정이 아니다. `[확정]`과 결정 번호(A14 등)가 붙은 것만 확정이다.

## 확정 사항 핵심

- 백엔드: Java 21 · Gradle(Groovy) · Spring Boot 4.2.0 · Spring Web/Security(세션)/Data JPA/Validation · MySQL 8 · Flyway · Lombok (A14)
- 프론트: React + Next.js 정적 빌드(`output: 'export'`). SSR · API Routes · 동적 경로 없음, ID가 들어가는 화면은 `?id=` (A14, ARCHITECTURE 3장)
- 배포 없음. 로컬에서 프론트(3000) · 백엔드(8080)를 따로 실행하고, 개발 모드에서만 Next.js가 `/api/**`를 8080으로 rewrites
- 모의 데이터만 쓴다(실제 개인정보 금지). 메시지는 가상 발송기만 쓴다(실제 문자 없음, A6)
- 스키마는 Flyway(`backend/src/main/resources/db/migration`, `V{n}__{설명}.sql`)로만 관리한다. `ddl-auto`는 `validate`
- Lombok: Entity에는 `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)`만 쓴다. `@Data` · `@Setter` 금지
- 도메인(백엔드 패키지) 사이: 다른 도메인의 엔티티는 ID로 참조하고, 그 도메인의 엔티티 · Repository를 import하지 않는다. 필요한 값은 쓰는 쪽 `port` 인터페이스로, 화면용 횡단 조회는 조회 전용 Repository의 DTO Projection으로 가져온다 (docs `DEVELOPMENT.md` 4장 「도메인 연동」, 2026-11-23 팀 결정)
- 코드 규칙(연관관계 · 주입 · 트랜잭션 · 로깅)은 docs `DEVELOPMENT.md` 4장을 따른다. 기준은 강사 코드 점검표다

## 로컬 환경 메모

- 실제 값은 루트 `.env` 하나에 둔다. `compose.yaml`이 자동으로 읽고, 백엔드는 `spring.config.import: optional:file:../.env[.properties]`로 읽는다. 그래서 백엔드 작업 디렉터리는 `backend/`여야 한다 (IntelliJ 실행 구성 Working directory 포함).
- `.env` 값은 Compose와 Spring(properties 형식)이 같이 읽는다. 따옴표 없이 쓰고 `$` `\` `#` 공백은 피한다.
- MySQL 계정 · 비밀번호는 볼륨을 처음 만들 때만 반영된다. 바꾸려면 그 DB의 볼륨을 지우고 다시 만들어야 하고, 그 볼륨의 데이터가 모두 사라진다. `docker compose down -v`는 개발 DB와 데모 DB 볼륨을 함께 지우므로 해결 방법으로 쓰지 않는다. `docker volume ls`로 대상 볼륨(개발 `keepsy_keepsy-mysql-data`, 데모 `keepsy_keepsy-mysql-demo-data`. 테스트 DB는 메모리라 볼륨 없음)을 확인하고, 지우기 전에 사용자에게 묻는다.
- 3306을 다른 MySQL이 쓰고 있으면 `.env`의 `MYSQL_PORT`를 바꾼다. compose와 백엔드가 같은 값을 쓴다. 테스트 · 데모 DB 포트는 `MYSQL_TEST_PORT` · `MYSQL_DEMO_PORT`(기본 3307 · 3308).

## 역할 분담

- 백엔드 공용 설정: 저장소 담당자
- 이슈 생성: 다른 조원 담당. 사용자가 요청하지 않으면 이슈를 만들지 않는다
- 프론트: `frontend/`(데모에서 전환, Keepsy #119)는 프론트 담당 조원이 맡는다

## 이슈를 만들 때 (요청받았을 때만)

- 템플릿은 Feature · Bug · Task. Feature는 요구사항 ID와 우선순위가 필수다.
- 단위는 요구사항명세서 ID 하나 또는 밀접한 몇 개. 「상세 / 수용 기준」 열을 완료 조건 체크리스트로 옮기고, 관련 결정 번호와 권한(명세서 2장 / CONTRACTS 3장 기능 키)을 적는다.
- 라벨은 종류(`feature` · `bug` · `chore`) + `area:<영역 코드>`만 쓴다. 정의는 `.github/labels.yml`.
- 우선순위 라벨(`priority:p1~p3`)과 마일스톤은 쓰지 않는다 (2026-11-23 팀 결정, docs `DEVELOPMENT.md` 3장). 명세서 우선순위는 Feature 본문 「우선순위」 칸에 적는다.
- 백엔드 이슈는 CONTRACTS 4장 엔드포인트 표를 근거로 한다.
- 프론트 이슈는 `frontend/` 화면을 ARCHITECTURE 3장(정적 빌드 제약) · 4장(프론트 구조, API 호출 규칙, 화면 경로 표)과 대조해 화면 경로 표의 행 단위로 만들고, 화면과 문서가 다른 곳은 따로 정리해 보고한다. 프론트 코드를 임의로 갈아엎거나 문서를 코드에 맞춰 바꾸지 않는다.
- 만들기 전에 목록(제목 · 라벨)을 먼저 보여 주고 확인을 받는다.

## 작업 원칙

- 의존성 · 설정 파일은 사용자가 IntelliJ에서 직접 만드는 것이 기본이다. "IntelliJ 메뉴 경로 → 파일 위치 → 붙여 넣을 내용" 순서로 안내하고, 직접 써 달라고 하면 그때 만든다.
- 버전 · 명령은 추측하지 말고 확인한다. 실제로 돌려서 확인된 실행 명령만 `../docs/docs/DEVELOPMENT.md` 6장 「실행 명령」에 반영한다.
- commit · push · 이슈 생성 · 저장소 설정 변경은 요청받을 때만 한다.

## 충돌 줄이기

여러 사람이 각자 Claude Code 세션에서 동시에 작업한다. 세션끼리는 서로의 진행 중인 작업을 모르므로 아래를 지킨다.

- **PR을 올리기 직전과 머지 직전에** `git fetch && git rebase origin/main` 후 테스트 DB(`mysql-test`)를 띄우고 `./gradlew build`(프론트면 `npm run lint` · `npm test` · `npm run build`, CI와 같음)를 다시 돌린다. 충돌은 작성자가 자기 세션에서 푼다.
- **PR을 쌓지 않는다.** 앞 PR이 머지된 뒤 `main`에서 새 브랜치를 딴다. 꼭 쌓았다면 앞 PR이 squash 머지된 직후 `git rebase --onto origin/main <앞 PR의 마지막 커밋>` 후 `git push --force-with-lease`(자기 브랜치만).
- 머지할 때 다른 PR이 base로 쓰는 브랜치는 지우지 않는다. 그 PR의 base를 `main`으로 바꾼 뒤 지운다 (지우면 그 PR이 닫힌다).
- **승인되면 작성자가 바로 머지한다.** 승인된 PR을 쌓아 두지 않는다. 작성자가 다른 세션 사용자라면 머지는 그 사람에게 맡긴다.
- **계약(docs) PR이 먼저 머지된 뒤** 코드 PR의 Draft를 푼다. docs PR은 올린 사람이 check 통과 후 바로 머지한다 (승인 불필요).
- 결정을 공유하는 메시지 끝에는 `반영: @담당 / 저장소`를 적고, 결정은 `../docs/결정체크리스트.md`에 기록한다 (Slack은 알림만).
- **공용 파일은 끝에 덧붙이지 말고 정해진 자리에 넣는다.** `../docs/docs/CONTRACTS.md` 6장은 4장 순서(ACC → CUS → RSV → VIS → PAY → PRD → CMP → …), enum · 목록(`ErrorCode`, `FeatureKey`, `support/TestShopData` 정리 목록 등)은 관련 값 옆에 넣는다.
- 공용 파일(`common/`, `application.yaml`, `SecurityConfig`, 테스트 지원 클래스)을 바꾸는 변경은 기능 PR과 나누어 작게 올린다.
