import { useEffect, useState } from 'react';
import { toast } from '@/components/common/Toast';
import { api, type Page } from '@/lib/api';
import { nameFromSearch } from '@/lib/newCustomer';
import { NewCustomerForm } from './NewCustomerForm';

// 고객 찾기: 이름 · 휴대폰 뒷 4자리로 검색해 고르거나, 없으면 바로 신규 등록한다 (FR-CUS-01 · 02, E3).
// 예약 등록 폼과 예약 상세의 [고객 연결]이 함께 쓴다. 연락처는 서버가 마스킹한 값 그대로 보여 준다 (NFR-04).

export type CustomerPick = { id: number; name: string };
type CustomerItem = { customerId: number; name: string; phone: string };

export function CustomerSearch(props: {
  /** 고를 수 없는 상태 (예: 「고객 없이 시간만 막기」). 켜면 입력과 신규 등록 칸이 막힌다 */
  disabled?: boolean;
  /** 고르면 부른다. `created`가 true면 방금 새로 등록한 고객이다 */
  onPick: (customer: CustomerPick, created: boolean) => void;
}) {
  const { disabled = false, onPick } = props;
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<CustomerItem[]>([]);
  const [creating, setCreating] = useState(false); // 신규 고객 등록 칸을 펼침

  // 입력이 멈추면 한 번만 부른다 (FR-CUS-02)
  useEffect(() => {
    const q = search.trim();
    if (!q) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      api<Page<CustomerItem>>(`/api/customers?q=${encodeURIComponent(q)}&size=8`, { silent: true })
        .then((p) => !cancelled && setResults(p.items))
        .catch(() => !cancelled && setResults([]));
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [search]);

  const reset = () => {
    setSearch('');
    setResults([]);
  };

  return (
    <>
      <input
        className="inp"
        placeholder="이름 또는 휴대폰 뒷 4자리로 검색"
        value={search}
        disabled={disabled || creating}
        onChange={(e) => setSearch(e.target.value)}
        autoFocus
      />
      {!creating && search.trim() && results.length > 0 ? (
        <ul className="cust-list">
          {results.map((c) => (
            <li key={c.customerId}>
              <button
                type="button"
                onClick={() => {
                  reset();
                  onPick({ id: c.customerId, name: c.name }, false);
                }}
              >
                <b>{c.name}</b>
                <span className="num">{c.phone}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {!creating && search.trim() && results.length === 0 ? (
        <p className="meta" style={{ marginTop: 6 }}>
          검색 결과가 없어요.
        </p>
      ) : null}
      {!disabled && !creating ? (
        <button type="button" className="linkbtn" onClick={() => setCreating(true)}>
          + 신규 고객 등록
        </button>
      ) : null}
      {!disabled && creating ? (
        <NewCustomerForm
          initialName={nameFromSearch(search)}
          onCreated={(c) => {
            setCreating(false);
            reset();
            toast('고객을 등록했어요.');
            onPick(c, true);
          }}
          onCancel={() => setCreating(false)}
        />
      ) : null}
    </>
  );
}
