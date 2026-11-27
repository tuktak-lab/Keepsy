// 고객 기본 정보 수정 (명세서 2장 「고객 기본 정보 수정」, 결정 F78). PATCH /api/customers/{id}는 칸을 통째로 받되,
// 번호는 비워 두면 서버가 지금 번호를 그대로 둔다. 화면은 전체 번호를 조회하지 않는다 (CONTACT_FULL을 따로 요구하지 않게).
import type { AcquisitionChannel, Gender } from '../features/customer/types';
import { t } from './i18n.ts';
import { PHONE_FORMAT } from './newCustomer.ts';

export type CustomerEditValues = {
  name: string;
  /** 비어 있으면 번호를 바꾸지 않는다 */
  phone: string;
  gender: Gender | '';
  birthYear: string;
  channel: AcquisitionChannel | '';
};

/** 첫 번째 입력 오류 사유. 없으면 null. 서버가 다시 검사한다 (NFR-08) */
export function validateCustomerEdit(v: CustomerEditValues, thisYear: number): string | null {
  const name = v.name.trim();
  if (!name) return t('customer.new.needName');
  if (name.length > 50) return t('customer.new.nameMax');
  const phone = v.phone.trim();
  if (phone && !PHONE_FORMAT.test(phone)) return t('customer.new.phoneFormat');
  const year = v.birthYear.trim();
  if (year && !(/^\d{4}$/.test(year) && Number(year) >= 1900 && Number(year) <= thisYear)) {
    return t('customer.edit.birthYearRange');
  }
  return null;
}

/** 빈 선택 칸은 null(서버가 비움), 번호는 비어 있으면 null(서버가 그대로 둠). 선호 언어는 보내지 않는다(서버가 그대로 둠, F76 ①) */
export function toCustomerEditRequest(v: CustomerEditValues) {
  return {
    name: v.name.trim(),
    phone: v.phone.trim() || null,
    gender: v.gender || null,
    birthYear: v.birthYear.trim() ? Number(v.birthYear.trim()) : null,
    acquisitionChannel: v.channel || null,
  };
}
