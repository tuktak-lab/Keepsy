import type { NextConfig } from 'next';
import { PHASE_DEVELOPMENT_SERVER } from 'next/constants';

// 정적 빌드(A14, docs ARCHITECTURE 3장)
// - `next build`는 `output: 'export'`로 `out/`에 정적 파일을 만든다.
// - `/api/**` → 백엔드(8080) rewrites는 개발 서버에서만 켠다. 정적 빌드에는 rewrites가 들어가지 않는다.
const BACKEND_URL = 'http://localhost:8080';

export default function nextConfig(phase: string): NextConfig {
  const base: NextConfig = {
    images: { unoptimized: true },
    // `next dev`가 AGENTS.md를 자동으로 만들지 않게 한다. AI 작업 규칙은 저장소 루트 CLAUDE.md · .claude/에 있다.
    agentRules: false,
    turbopack: {
      rules: {
        // 데모 화면 CSS: `import css from './X.css?inline'`를 문자열로 받아
        // useStyleSheet가 그 화면이 떠 있는 동안에만 <head>에 넣는다 (화면끼리 클래스 이름이 겹치기 때문).
        // 화면별 CSS Modules 전환은 각 화면 이슈에서 한다 (docs DEVELOPMENT 4장).
        '*.css': {
          condition: { query: /[?&]inline(?=&|$)/ },
          type: 'raw',
        },
      },
    },
  };

  if (phase === PHASE_DEVELOPMENT_SERVER) {
    return {
      ...base,
      async rewrites() {
        return [{ source: '/api/:path*', destination: `${BACKEND_URL}/api/:path*` }];
      },
    };
  }

  return { ...base, output: 'export' };
}
