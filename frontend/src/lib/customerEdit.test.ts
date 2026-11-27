// node --test 로 돌린다: 고객 기본 정보 수정 (명세서 2장, F78)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { toCustomerEditRequest, validateCustomerEdit, type CustomerEditValues } from './customerEdit.ts';

const base: CustomerEditValues = { name: '모의 고객', phone: '', gender: '', birthYear: '', channel: '' };

test('validateCustomerEdit: 번호는 비워도 되고, 적으면 형식을 본다', () => {
  assert.equal(validateCustomerEdit(base, 2026), null);
  assert.equal(validateCustomerEdit({ ...base, phone: '010-0000-9999' }, 2026), null);
  assert.equal(validateCustomerEdit({ ...base, phone: '01000009999' }, 2026), null);
  assert.match(validateCustomerEdit({ ...base, phone: '02-000-0000' }, 2026)!, /010/);
});

test('validateCustomerEdit: 이름 필수 · 50자, 출생 연도는 1900~올해', () => {
  assert.match(validateCustomerEdit({ ...base, name: '  ' }, 2026)!, /이름/);
  assert.match(validateCustomerEdit({ ...base, name: '가'.repeat(51) }, 2026)!, /50자/);
  assert.equal(validateCustomerEdit({ ...base, birthYear: '2026' }, 2026), null);
  for (const y of ['2027', '1899', '95', '19x5']) {
    assert.match(validateCustomerEdit({ ...base, birthYear: y }, 2026)!, /출생 연도/, y);
  }
});

test('toCustomerEditRequest: 빈 번호는 null(그대로 둠), 빈 선택 칸은 null(비움), 선호 언어는 보내지 않는다', () => {
  assert.deepEqual(toCustomerEditRequest({ ...base, name: ' 바뀐 고객 ' }), {
    name: '바뀐 고객',
    phone: null,
    gender: null,
    birthYear: null,
    acquisitionChannel: null,
  });
  assert.deepEqual(
    toCustomerEditRequest({ name: '모의 고객', phone: ' 010-0000-9999 ', gender: 'FEMALE', birthYear: '1995', channel: 'INSTAGRAM' }),
    { name: '모의 고객', phone: '010-0000-9999', gender: 'FEMALE', birthYear: 1995, acquisitionChannel: 'INSTAGRAM' },
  );
});
