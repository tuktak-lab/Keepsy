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

/** onRetry를 주면 그 영역만 다시 불러오는 버튼을 함께 보인다. 저장 · 삭제 같은 동작 오류에는 주지 않는다 */
export function AreaError(props: { text: string; onRetry?: () => unknown }) {
  return props.text ? (
    <p className="note" role="alert" style={{ color: '#8A2F3F' }}>
      {props.text}
      {props.onRetry && (
        <>
          {' '}
          <button type="button" className="lk" onClick={() => void props.onRetry?.()}>
            {t('common.retry')}
          </button>
        </>
      )}
    </p>
  ) : null;
}
