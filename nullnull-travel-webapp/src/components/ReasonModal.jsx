import { X } from 'lucide-react';
import { Button, Card } from './common';
import { BREAKDOWN_ROWS } from '../constants';

// 추천 이유 모달 — AlternativeScore(9-2) 점수 구성을 막대로 시각화
export default function ReasonModal({ item, onClose }) {
  const breakdown = item.breakdown ?? {};
  const loadPenalty = breakdown.load_penalty ?? 0;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <Card className="modal-card">
        <button className="modal-close" onClick={onClose} aria-label="닫기">
          <X size={18} />
        </button>
        <img src={item.image} alt={item.title} />
        <h2>{item.title}</h2>
        <p>{item.reason}</p>

        {item.breakdown && (
          <div className="breakdown">
            <span className="eyebrow">추천 점수 구성 (종합 {item.score?.toFixed(2)})</span>
            {BREAKDOWN_ROWS.map(({ key, label }) => {
              const value = breakdown[key];
              if (value === null || value === undefined) {
                return (
                  <div className="proof-bar is-muted" key={key}>
                    <div>
                      <span>{label}</span>
                      <strong>예보 범위 밖 — 제외</strong>
                    </div>
                    <i>
                      <b style={{ width: 0 }} />
                    </i>
                  </div>
                );
              }
              const negative = value < 0;
              return (
                <div className={`proof-bar ${negative ? 'is-negative' : ''}`} key={key}>
                  <div>
                    <span>{label}</span>
                    <strong>
                      {negative ? '' : '+'}
                      {Math.round(value * 100)}%
                    </strong>
                  </div>
                  <i>
                    <b style={{ width: `${Math.min(Math.abs(value) * 100, 100)}%` }} />
                  </i>
                </div>
              );
            })}
            <div className={`proof-bar ${loadPenalty > 0 ? 'is-negative' : 'is-muted'}`}>
              <div>
                <span>추천 쏠림 조정</span>
                <strong>{loadPenalty > 0 ? `−${Math.round(loadPenalty * 100)}%` : '없음'}</strong>
              </div>
              <i>
                <b style={{ width: `${Math.min(loadPenalty * 1000, 100)}%` }} />
              </i>
            </div>
          </div>
        )}

        <Button full onClick={onClose}>
          확인
        </Button>
      </Card>
    </div>
  );
}
