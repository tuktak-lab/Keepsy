# Keepsy

> 네일·속눈썹·왁싱·반영구 시술을 제공하는 2인 이상 뷰티샵을 위한 고객관리(CRM) 웹 플랫폼입니다.

고객의 방문 이력, 시술 사진, 취향, 상담 메모를 한곳에 기록하고, 고객별 방문 주기를 분석해 다음 방문 시점을 예측합니다. 이를 바탕으로 재방문이 필요한 고객을 파악하고, 검토된 메시지 캠페인부터 재예약·재방문 성과 확인까지 연결합니다.

Claude Design 캔버스를 옮긴 데모(Keepsy-design@6ac161c)를 **Next.js 정적 빌드**(`output: 'export'`, A14)로 전환한 것입니다 (#119). 아직 **화면만** 있고 API 연동은 없습니다(목업 데이터·동작은 디자인 그대로). 화면 기능과 API 연결은 화면별 이슈에서 합니다.

- Node.js 24 (`.nvmrc`, `package.json` `engines`)
- Next.js 16 (App Router, Turbopack) · React 19 · TypeScript

## 실행

`frontend/`에서 실행합니다.

```bash
npm install
npm run dev     # http://localhost:3000, /api/** 는 백엔드(8080)로 넘김
npm run build   # 정적 파일을 out/ 에 만듦
npm run lint
```

- `/api/**` → `http://localhost:8080` rewrites는 개발 서버에서만 켜집니다. 정적 빌드에는 들어가지 않습니다 (docs ARCHITECTURE 3장).
- 정적 빌드 제약: SSR · API Routes · Server Actions · 미들웨어 · 동적 경로를 쓰지 않습니다. ID가 들어가는 화면은 `?id=` 쿼리로 받습니다.

## 주소

docs ARCHITECTURE 4장 「화면 경로」 기준입니다.

| 화면 | 주소 | 데모 주소 |
|---|---|---|
| 01 CRM 대시보드 | /dashboard (`/`는 여기로 이동) | / |
| 02 예약 관리 | /reservations | /schedule |
| 03 고객 목록 | /customers | |
| 04 고객 상세 | /customers/detail?id= | /customers/detail |
| 05 시술 히스토리북 | /history (고객 상세에 합칠 때까지 임시) | |
| 06 재방문 관리(인사이트) | /insights | |
| 07 캠페인 관리 | /campaigns | |
| 08 성과 분석 | /analytics | /performance |
| 09 매장 설정 | /settings | |
| 00 디자인 시스템 (개발용) | /design-system | |

## 폴더

docs ARCHITECTURE 4장 「프론트 구조」 기준입니다.

```
src/
  app/                화면 경로별 page.tsx (모두 'use client')
  features/<영역>/     영역별 화면 (백엔드 패키지와 같은 이름: dashboard, reservation, customer, prediction, campaign, analytics, settings)
  components/common/  Avatar, Photo, AppLink
  components/layout/  Sidebar, Topbar
  hooks/              useStyleSheet (화면별 CSS 적용/해제)
  assets/images/      고객 사진·시술 사진 + index.ts
  styles/base.css     모든 화면 공통 CSS
  types/              타입 선언 (`*.css?inline`)
```

- `src/lib/api.ts`(API 호출 래퍼)는 #120에서 만듭니다.
- 화면별 CSS는 그 화면이 떠 있는 동안에만 적용됩니다. 데모 화면들은 `.card` · `.btn` 같은 클래스 이름이 겹치고 정의가 달라서, 전역 CSS로 합치면 화면이 깨집니다. 그래서 `import css from './X.css?inline'`(next.config.ts의 turbopack 규칙으로 문자열로 받음)과 `useStyleSheet`를 그대로 씁니다. CSS Modules 전환은 각 화면 이슈에서 합니다 (docs DEVELOPMENT 4장).
- 이미지 import는 Next.js에서 `{ src, width, height }` 객체라 `.src`로 URL을 꺼내 씁니다.
- 글꼴 Noto Sans KR은 Google Fonts에서 불러옵니다 (`src/app/layout.tsx`).
- 상태 타입은 우선 any로 느슨하게 두었습니다(strict: false). 그래서 `npm run lint`에 데모 코드의 `no-explicit-any` 오류가 남아 있습니다.
