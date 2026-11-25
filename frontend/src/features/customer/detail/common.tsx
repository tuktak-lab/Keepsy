import { ApiError } from '@/lib/api';

// 고객 상세 영역들이 같이 쓰는 작은 조각

/** 영역마다 다른 기능 키(명세서 2장)라, 실패하면 그 영역에만 사유를 보여 준다 */
export function errorText(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'pin_required' || err.code === 'owner_pin_required') return 'PIN 인증이 필요한 영역이에요.';
    return err.message;
  }
  return '잠시 후 다시 시도해 주세요';
}

export function AreaError(props: { text: string }) {
  return props.text ? (
    <p className="note" role="alert" style={{ color: '#8A2F3F' }}>
      {props.text}
    </p>
  ) : null;
}
