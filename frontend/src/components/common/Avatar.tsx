// 고객 아바타. 고객 프로필 사진 필드는 API · DB에 없으므로 이름의 첫 글자(이니셜)만 보여 준다.
// `photo`는 디자인 시스템 화면처럼 사진을 보여 줘야 할 때 호출하는 쪽이 명시적으로 넘기는 값이다 (이름으로 고르지 않는다).
export function Avatar(props: { who?: string; size?: number | string; photo?: string }) {
  const name = props.who || '';
  const size = Number(props.size) || 36;

  return (
    <span
      style={{
        display: 'block',
        flex: 'none',
        width: `${size}px`,
        height: `${size}px`,
        borderRadius: '50%',
        overflow: 'hidden',
        background: '#ECE8E3',
        position: 'relative',
      }}
    >
      {props.photo ? (
        <img
          src={props.photo}
          alt={name}
          style={{ display: 'block', width: '100%', height: '100%', objectFit: 'cover', objectPosition: '50% 22%' }}
        />
      ) : (
        <span
          style={{
            display: 'flex',
            width: '100%',
            height: '100%',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#4A3F3B',
            font: `700 ${Math.round(size * 0.4)}px 'Noto Sans KR', sans-serif`,
          }}
        >
          {name.charAt(0)}
        </span>
      )}
    </span>
  );
}
