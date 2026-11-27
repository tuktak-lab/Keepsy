import type { MessageKey } from './ko.ts';

export const en: Record<MessageKey, string> = {
  'locale.label': 'Language',
  'format.won': '₩{amount}',
};

/** 오류 code별 안내 (F74 ③). 서버 code는 CONTRACTS 2장 · ErrorCode.java, 나머지 셋은 api.ts가 만드는 code */
export const enErrors: Record<string, string> = {
  unauthorized: 'Please sign in.',
  login_failed: 'The email or password is incorrect.',
  login_locked: 'Too many sign-in attempts. Please try again later.',
  worker_required: 'Enter the staff PIN first.',
  pin_invalid: 'The PIN is incorrect.',
  pin_locked: 'Too many PIN attempts. Please try again later.',
  password_mismatch: 'The password is incorrect.',
  pin_required: 'Enter your PIN.',
  owner_pin_required: 'The owner PIN is required.',
  not_found: 'We could not find what you are looking for.',
  invalid: 'Please check what you entered.',
  csrf_invalid: 'Your request has expired. Refresh the page and try again.',
  duplicate: 'This is already registered.',
  reservation_conflict: 'This time cannot be booked.',
  consent_required: 'Consent is required.',
  review_required: 'Confirm that you have reviewed the recipients and the message.',
  night_send_blocked: 'Messages cannot be sent immediately at night (9 PM to 8 AM).',
  daily_limit_exceeded: "Today's sending limit has been exceeded.",
  conflict: 'This cannot be done in the current state.',
  internal_error: 'A temporary error occurred. Please try again later.',
  network_error: 'Could not connect to the server. Check your internet connection and try again.',
  invalid_response: 'Please try again later.',
};
