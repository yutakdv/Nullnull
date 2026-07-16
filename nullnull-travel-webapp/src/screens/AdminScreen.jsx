// F8 로테이션·수집 상태 시연 화면(데모 시나리오 ⑦) — #admin 해시 + 토큰으로 진입
import { useState } from 'react';
import { Loader2, ShieldCheck } from 'lucide-react';
import { fetchImpactSummary, fetchIngestLog } from '../api/endpoints';
import { Button, Card, SectionHeader, Tag } from '../components/common';
import { STORAGE_KEYS } from '../services/storage';

export default function AdminScreen({ onExit }) {
  const [token, setToken] = useState(() => sessionStorage.getItem(STORAGE_KEYS.adminToken) ?? '');
  const [data, setData] = useState(null);
  const [impactData, setImpactData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const response = await fetchIngestLog(token);
      sessionStorage.setItem(STORAGE_KEYS.adminToken, token);
      setData(response);
      setImpactData(await fetchImpactSummary().catch(() => null));
    } catch (err) {
      setData(null);
      setError(err.message ?? '불러오지 못했어요');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="screen admin-screen">
      <Card className="admin-head">
        <div>
          <Tag icon={ShieldCheck}>관리자</Tag>
          <h1>수집 상태 · 추천 부하 분포(F8)</h1>
          <p>공사 OpenAPI 수집 로그와 대안지 로테이션 현황을 확인합니다.</p>
        </div>
        <button className="reason-button" onClick={onExit}>
          서비스 화면으로
        </button>
      </Card>

      <Card className="admin-token-card">
        <label className="search-bar">
          <ShieldCheck size={18} />
          <input
            type="password"
            value={token}
            placeholder="X-Admin-Token"
            onChange={(event) => setToken(event.target.value)}
            onKeyDown={(event) => event.key === 'Enter' && load()}
          />
        </label>
        <Button onClick={load} disabled={loading || !token}>
          {loading ? <Loader2 size={17} className="spin" /> : '불러오기'}
        </Button>
      </Card>
      {error && <p className="admin-error">{error}</p>}

      {data && (
        <>
          {impactData?.dispersion_lift && (
            <Card>
              <SectionHeader title="분산 리프트(최근 7일)" compact />
              <p className="calendar-note">
                대안 노출 {impactData.dispersion_lift.exposed.toLocaleString()}건 중{' '}
                {impactData.dispersion_lift.selected.toLocaleString()}건 선택 — 전환율{' '}
                <strong>{impactData.dispersion_lift.conversion_pct}%</strong>, 선택된 대안의 예상
                혼잡 감소율 평균{' '}
                <strong>{impactData.dispersion_lift.avg_realized_decrease_pct}%</strong>
                {impactData.includes_seed ? ' · 예시 데이터 포함' : ''}
              </p>
            </Card>
          )}

          <Card>
            <SectionHeader title="대안지 추천 부하(최근 7일)" compact />
            <p className="calendar-note">
              노출 + 선택×2를 후보군 내 최대값으로 정규화한 값 — 부하가 높을수록 다음 추천에서
              페널티를 받아 자연 로테이션됩니다.
            </p>
            <div className="proof-bars">
              {data.load_distribution.slice(0, 10).map((row) => (
                <div className="proof-bar" key={row.spot_id}>
                  <div>
                    <span>
                      {row.name} · 노출 {row.exposures} / 선택 {row.selections}
                    </span>
                    <strong>{Math.round(row.load * 100)}%</strong>
                  </div>
                  <i>
                    <b style={{ width: `${row.load * 100}%` }} />
                  </i>
                </div>
              ))}
            </div>
          </Card>

          <Card>
            <SectionHeader title="공사 API 수집 로그" compact />
            <div className="ingest-table">
              {data.ingest.map((log, index) => (
                <div className={`ingest-row is-${log.status}`} key={`${log.api_name}-${index}`}>
                  <strong>{log.api_name}</strong>
                  <span>
                    {log.status}
                    {log.records ? ` · ${log.records}건` : ''}
                  </span>
                  <small>{log.last_synced_at.replace('T', ' ').slice(0, 16)}</small>
                  {log.error_message && <p>{log.error_message}</p>}
                </div>
              ))}
            </div>
          </Card>
        </>
      )}
    </section>
  );
}
