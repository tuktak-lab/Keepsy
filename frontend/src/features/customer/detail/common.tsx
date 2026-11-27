import { ApiError } from '@/lib/api';
import { t } from '@/lib/i18n';

// 고객 상세 영역들이 같이 쓰는 작은 조각

/** 영역마다 다른 기능 키(명세서 2장)라, 실패하면 그 영역에만 사유를 보여 준다 */
export function errorText(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'pin_required' || err.code === 'owner_pin_required') return t('customer.detailCommon.pinNeeded');
    return err.message;
  }
  return t('common.pin.tryLater');
}

export function AreaError(props: { text: string }) {
  return props.text ? (
    <p className="note" role="alert" style={{ color: '#8A2F3F' }}>
      {props.text}
    </p>
  ) : null;
}
