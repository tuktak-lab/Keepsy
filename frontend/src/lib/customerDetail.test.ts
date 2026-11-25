// 실행: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { predictionText, rhythmPoints, seoulToday, shortDate } from './customerDetail.ts';
import type { CategoryPrediction, HistoryVisit } from '../features/customer/types';

const base: CategoryPrediction = {
  category: 'NAIL',
  status: 'SCHEDULED',
  lastServiceDate: '2026-11-01',
  startDate: '2026-11-22',
  endDate: '2026-11-29',
  averageIntervalDays: 24,
  basisVisitCount: 6,
  defaultCycleBased: false,
  endSignal: false,
};

test('예측 문구: 평균 간격 · 방문 수, 시술 평균 주기 기준', () => {
  assert.equal(predictionText(base, '2026-11-01'), '약 3~4주 후 (평균 24일 · 방문 6회)');
  assert.equal(predictionText({ ...base, defaultCycleBased: true }, '2026-11-01'), '약 3~4주 후 (시술 평균 주기 기준)');
});

test('예측 문구: 기간 안 · 지남 · 예측 없음 · 종료 신호', () => {
  assert.equal(predictionText(base, '2026-11-24'), '약 1주 안 (평균 24일 · 방문 6회)');
  assert.equal(predictionText(base, '2026-12-10'), '예측 기간 지남 (평균 24일 · 방문 6회)');
  assert.equal(predictionText({ ...base, status: 'NO_PREDICTION', startDate: null, endDate: null }, '2026-11-01'), '예측 없음');
  assert.equal(predictionText({ ...base, endSignal: true, startDate: '2026-10-02', endDate: '2026-10-02' }, '2026-11-01'), '종료 신호 (10.02)');
});

test('방문 리듬: 날짜 오름차순, 카테고리 중복 없이', () => {
  const v = (visitDate: string, cats: HistoryVisit['services'][number]['category'][]): HistoryVisit => ({
    visitId: 1,
    visitDate,
    serviceCount: cats.length,
    services: cats.map((category) => ({ category, menuName: null, staff: null })),
    photos: [],
    notes: [],
  });
  assert.deepEqual(rhythmPoints([v('2026-10-24', ['NAIL', 'NAIL']), v('2026-09-28', ['LASH'])]), [
    { date: '2026-09-28', categories: ['LASH'] },
    { date: '2026-10-24', categories: ['NAIL'] },
  ]);
});

test('날짜 표시 · 서울 오늘', () => {
  assert.equal(shortDate('2026-10-24'), '10.24');
  assert.equal(seoulToday(new Date('2026-11-24T16:00:00Z')), '2026-11-25');
});
