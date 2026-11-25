import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_IMPORT_BYTES, fileProblem, templateCsv } from './customerImport.ts';

test('템플릿은 UTF-8 BOM과 계약의 헤더 한 줄', () => {
  assert.equal(templateCsv(), '﻿이름,휴대폰,성별,출생연도,메모,방문일,시술카테고리\r\n');
});

test('CSV가 아니거나 비었거나 2MB를 넘으면 올리지 않는다', () => {
  assert.match(fileProblem({ name: 'customers.xlsx', size: 10 }) ?? '', /CSV/);
  assert.match(fileProblem({ name: 'customers.csv', size: 0 }) ?? '', /빈 파일/);
  assert.match(fileProblem({ name: 'customers.csv', size: MAX_IMPORT_BYTES + 1 }) ?? '', /2MB/);
});

test('2MB까지는 통과하고 확장자 대소문자는 가리지 않는다', () => {
  assert.equal(fileProblem({ name: 'customers.CSV', size: MAX_IMPORT_BYTES }), null);
});
