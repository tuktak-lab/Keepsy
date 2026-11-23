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
- Lombok: Entity에는 `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)`만 쓴다. `@Data` 금지

## 로컬 환경 메모

- 실제 값은 루트 `.env` 하나에 둔다. `compose.yaml`이 자동으로 읽고, 백엔드는 `spring.config.import: optional:file:../.env[.properties]`로 읽는다. 그래서 백엔드 작업 디렉터리는 `backend/`여야 한다 (IntelliJ 실행 구성 Working directory 포함).
- `.env` 값은 Compose와 Spring(properties 형식)이 같이 읽는다. 따옴표 없이 쓰고 `$` `\` `#` 공백은 피한다.
- MySQL 계정 · 비밀번호는 볼륨을 처음 만들 때만 반영된다. 바꾸려면 `docker compose down -v`(데이터 삭제)가 필요하다.
- 3306을 다른 MySQL이 쓰고 있으면 `.env`의 `MYSQL_PORT`를 바꾼다. compose와 백엔드가 같은 값을 쓴다.

## 역할 분담

- 백엔드 골격 · 설정: 저장소 담당자
- 이슈 생성: 다른 조원 담당. 사용자가 요청하지 않으면 이슈를 만들지 않는다
- 프론트: 다른 조원이 데모를 `frontend/`에 올릴 예정. 올라오기 전에는 프론트 코드 · 이슈를 만들지 않는다

## 이슈를 만들 때 (요청받았을 때만)

- 템플릿은 Feature · Bug · Task. Feature는 요구사항 ID와 우선순위가 필수다.
- 단위는 요구사항명세서 ID 하나 또는 밀접한 몇 개. 「상세 / 수용 기준」 열을 완료 조건 체크리스트로 옮기고, 관련 결정 번호와 권한(명세서 2장 / CONTRACTS 3장 기능 키)을 적는다.
- 라벨은 종류(`feature` · `bug` · `chore`) + `area:<영역 코드>`만 쓴다. 정의는 `.github/labels.yml`.
- 우선순위 라벨(`priority:p1~p3`)과 마일스톤은 쓰지 않는다 (2026-11-23 팀 결정, docs `DEVELOPMENT.md` 3장). 명세서 우선순위는 Feature 본문 「우선순위」 칸에 적는다.
- 백엔드 이슈는 CONTRACTS 4장 엔드포인트 표를 근거로 한다.
- 프론트 이슈는 데모가 `frontend/`에 올라온 뒤에만 만든다. 데모를 ARCHITECTURE 3장(정적 빌드 제약) · 4장(프론트 구조, API 호출 규칙, 화면 경로 표)과 대조해 화면 경로 표의 행 단위로 만들고, 데모와 문서가 다른 곳은 따로 정리해 보고한다. 데모 코드를 임의로 갈아엎거나 문서를 데모에 맞춰 바꾸지 않는다.
- 만들기 전에 목록(제목 · 라벨)을 먼저 보여 주고 확인을 받는다.

## 작업 원칙

- 의존성 · 설정 파일은 사용자가 IntelliJ에서 직접 만드는 것이 기본이다. "IntelliJ 메뉴 경로 → 파일 위치 → 붙여 넣을 내용" 순서로 안내하고, 직접 써 달라고 하면 그때 만든다.
- 버전 · 명령은 추측하지 말고 확인한다. 실제로 돌려서 확인된 실행 명령만 `../docs/docs/DEVELOPMENT.md` 6장 「실행 명령」에 반영한다.
- commit · push · 이슈 생성 · 저장소 설정 변경은 요청받을 때만 한다.
