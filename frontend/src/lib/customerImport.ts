// CSV 고객 일괄 등록 (FR-CUS-13). docs CONTRACTS 6장 `POST /api/customers/import`.
// 파일 내용 검사(헤더 · 행 수 · 인코딩 · 각 행)는 서버가 한다. 화면은 올리기 전에 크기만 미리 막는다.

import { t } from './i18n.ts';

/** 서비스 CSV 템플릿의 헤더. 이름 · 휴대폰만 필수 열이고 순서는 상관없다 */
export const IMPORT_COLUMNS = ['이름', '휴대폰', '성별', '출생연도', '메모', '방문일', '시술카테고리'] as const;

/** 파일 크기 상한 2MB (2,097,152바이트) */
export const MAX_IMPORT_BYTES = 2 * 1024 * 1024;

/** 템플릿 파일 내용: UTF-8 BOM + 헤더 한 줄 (엑셀에서 한글이 깨지지 않게 BOM을 붙인다) */
export function templateCsv(): string {
  return `﻿${IMPORT_COLUMNS.join(',')}\r\n`;
}

/** 올리기 전에 막을 사유. 없으면 null */
export function fileProblem(file: { name: string; size: number }): string | null {
  if (!/\.csv$/i.test(file.name)) return t('customer.import.csvOnly');
  if (file.size === 0) return t('customer.import.empty');
  if (file.size > MAX_IMPORT_BYTES) return t('customer.import.tooBig');
  return null;
}

/** 결과 요약 (응답 그대로) */
export type ImportResult = {
  createdCustomers: number;
  addedVisits: number;
  addedNotes: number;
  skippedDuplicates: number;
  errorCount: number;
  errors: { row: number; reason: string }[];
};
