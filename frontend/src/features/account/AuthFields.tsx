import { useState, type InputHTMLAttributes } from 'react';
import styles from './AuthPage.module.css';

// 로그인 · 가입 폼이 같이 쓰는 작은 조각 (아이콘, 비밀번호 보기 칸)

const ICON = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const;

export const ChevronIcon = () => (
  <svg {...ICON} width={16} height={16} strokeWidth={2.2}>
    <path d="M9 6l6 6-6 6" />
  </svg>
);

export const InfoIcon = () => (
  <svg {...ICON} width={15} height={15}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v5M12 8h.01" />
  </svg>
);

const EyeIcon = () => (
  <svg {...ICON}>
    <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

const EyeOffIcon = () => (
  <svg {...ICON}>
    <path d="M3 3l18 18M10.6 5.7A9.9 9.9 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16 16 0 0 1-3.2 3.9M6.6 6.9A15.7 15.7 0 0 0 2.5 12S6 18.5 12 18.5a9.6 9.6 0 0 0 4-.9M9.9 9.9a3 3 0 0 0 4.2 4.2" />
  </svg>
);

/** 오른쪽 끝에 「보기 / 숨기기」 버튼이 붙은 비밀번호 칸 */
export function PasswordInput(props: Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  const [shown, setShown] = useState(false);
  const { className, ...rest } = props;
  return (
    <span className={styles.inputWrap}>
      <input {...rest} className={`${styles.input} ${styles.hasIcon} ${className ?? ''}`} type={shown ? 'text' : 'password'} />
      <button type="button" className={styles.iconBtn} onClick={() => setShown((s) => !s)} aria-label={shown ? '비밀번호 숨기기' : '비밀번호 보기'} aria-pressed={shown}>
        {shown ? <EyeOffIcon /> : <EyeIcon />}
      </button>
    </span>
  );
}
