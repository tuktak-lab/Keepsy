'use client';

import { useEffect, useRef, useState } from 'react';
import { ApiError, type PinPrompt } from '@/lib/api';
import { authenticatePin } from '@/lib/session';

// 잠긴 기능 PIN 입력 (FR-ACC-08). PIN 확인은 서버(POST /api/session/pin)가 한다 (NFR-04).
// 원장 PIN도 같은 API로 인증하고, 원장인지는 서버가 잠긴 기능 요청에서 검사한다 (ARCHITECTURE 6장).

const PIN_FORMAT = /^\d{4,6}$/;

export function PinForm(props: { title: string; owner?: boolean; message?: string; onDone: () => void; onCancel?: () => void }) {
  const [pin, setPin] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  // 처음 열 때와, 확인이 끝나 입력칸이 다시 켜졌을 때 포커스 (disabled인 동안에는 focus()가 먹지 않는다)
  useEffect(() => {
    if (!busy) input.current?.focus();
  }, [busy]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!PIN_FORMAT.test(pin)) {
      setError('PIN은 숫자 4~6자리입니다');
      return;
    }
    setBusy(true);
    try {
      await authenticatePin(pin);
      props.onDone();
    } catch (err) {
      // pin_invalid · pin_locked · invalid는 서버 message를 그대로 보여 준다. 그 밖의 권한 code는 api.ts가 화면을 옮긴다
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요');
      setPin('');
    } finally {
      setBusy(false);
    }
  }

  const guide = props.owner
    ? '원장 PIN이 필요한 기능입니다. 원장 PIN을 입력해 주세요.'
    : '잠긴 기능입니다. 사용이 허용된 스탭의 PIN을 입력해 주세요.';

  return (
    <form
      onSubmit={submit}
      style={{
        width: '360px',
        maxWidth: '100%',
        boxSizing: 'border-box',
        padding: '32px 28px',
        borderRadius: '24px',
        background: 'rgba(255,255,255,0.66)',
        border: '1px solid rgba(255,255,255,0.85)',
        boxShadow: 'inset 0 1px 0 #fff, 0 0 0 1px rgba(150,100,108,0.12), 0 24px 60px -12px rgba(150,90,100,0.3)',
        WebkitBackdropFilter: 'blur(24px) saturate(1.15)',
        backdropFilter: 'blur(24px) saturate(1.15)',
        textAlign: 'center',
        display: 'flex',
        flexDirection: 'column',
        gap: '14px',
        fontFamily: "'Noto Sans KR', sans-serif",
      }}
    >
      {/* 사람 + 자물쇠 아이콘 (Lucide user-lock 모양, ISC) */}
      <svg
        width="32"
        height="32"
        viewBox="0 0 24 24"
        fill="none"
        stroke="#7A4450"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        style={{ alignSelf: 'center' }}
        aria-hidden="true"
      >
        <circle cx="10" cy="7" r="4" />
        <path d="M10.3 15H7a4 4 0 0 0-4 4v2" />
        <path d="M15 15.5V14a2 2 0 0 1 4 0v1.5" />
        <rect width="8" height="5" x="13" y="16" rx=".9" />
      </svg>
      <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 600, color: '#221D1B' }}>{props.title}</h2>
      <p style={{ margin: 0, fontSize: '13px', color: '#6B605B', lineHeight: 1.6 }}>{guide}</p>
      {props.message && <p style={{ margin: 0, fontSize: '13px', color: '#7A4450', lineHeight: 1.6 }}>{props.message}</p>}
      <input
        ref={input}
        type="password"
        inputMode="numeric"
        autoComplete="off"
        maxLength={6}
        value={pin}
        disabled={busy}
        onChange={(e) => {
          setPin(e.target.value.replace(/\D/g, ''));
          setError('');
        }}
        aria-label="PIN"
        aria-invalid={error ? true : undefined}
        placeholder="PIN"
        style={{
          height: '46px',
          borderRadius: '12px',
          border: `1px solid ${error ? '#C98B8B' : 'rgba(255,255,255,0.9)'}`,
          background: 'rgba(255,255,255,0.6)',
          boxShadow: 'inset 0 1px 2px rgba(150,90,100,0.08), 0 0 0 1px rgba(150,100,108,0.12)',
          textAlign: 'center',
          fontSize: '18px',
          letterSpacing: '.3em',
          outline: 0,
        }}
      />
      {error && (
        <span role="alert" style={{ fontSize: '12px', color: '#8F2F2F' }}>
          {error}
        </span>
      )}
      <button
        type="submit"
        disabled={busy}
        style={{
          height: '46px',
          border: '1px solid rgba(255,255,255,0.18)',
          borderRadius: '12px',
          background: 'linear-gradient(180deg, #8c5262 0%, #74404c 100%)',
          boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.28), 0 8px 20px -6px rgba(122,68,80,0.5)',
          color: '#fff',
          font: "500 14px 'Noto Sans KR', sans-serif",
          cursor: 'pointer',
        }}
      >
        {busy ? '확인 중…' : '열기'}
      </button>
      {props.onCancel && (
        <button
          type="button"
          onClick={props.onCancel}
          style={{ height: '40px', border: 0, borderRadius: '12px', background: 'transparent', color: '#6B605B', font: "400 13px 'Noto Sans KR', sans-serif", cursor: 'pointer' }}
        >
          취소
        </button>
      )}
    </form>
  );
}

// api.ts의 promptPin 자리: pin_required · owner_pin_required를 받으면 이 창을 띄우고, 인증되면 true
type Open = PinPrompt & { resolve: (ok: boolean) => void };
let show: ((open: Open) => void) | null = null;

export function openPinDialog(prompt: PinPrompt): Promise<boolean> {
  return new Promise((resolve) => {
    if (!show) return resolve(false);
    show({ ...prompt, resolve });
  });
}

export function PinDialogHost() {
  const [open, setOpen] = useState<Open | null>(null);

  useEffect(() => {
    show = setOpen;
    return () => {
      show = null;
    };
  }, []);

  if (!open) return null;

  function close(ok: boolean) {
    open?.resolve(ok);
    setOpen(null);
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="PIN 입력"
      onKeyDown={(e) => e.key === 'Escape' && close(false)}
      style={{ position: 'fixed', inset: 0, zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px', background: 'rgba(34,29,27,.32)' }}
    >
      <PinForm
        title={open.owner ? '원장 PIN' : 'PIN 인증'}
        owner={open.owner}
        message={open.message}
        onDone={() => close(true)}
        onCancel={() => close(false)}
      />
    </div>
  );
}
