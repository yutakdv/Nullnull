"""가중치 백테스트(9-5) — 스크립트가 시드 데이터로 절차 검증을 통과하는지."""
from scripts.backtest import render_markdown, run, spearman


def test_spearman_rank_correlation():
    assert spearman([1, 2, 3, 4], [10, 20, 30, 40]) == 1.0
    assert spearman([1, 2, 3, 4], [40, 30, 20, 10]) == -1.0
    assert abs(spearman([1, 2, 3, 4], [10, 10, 10, 10])) == 0.0  # 동순위 전부


def test_backtest_reproduces_expected_ordering(db):
    result = run(db)

    # ② 대표 명소 > 소규모 명소: 방문 규모와 위험도의 순위 상관이 높아야 한다
    assert result["scale_spearman"] >= 0.8

    # ① 주말 > 평일(공휴일은 창 내 존재 시): 과반 스팟에서 재현
    assert result["day_order_pass"] >= result["day_order_total"] * 0.7

    report = render_markdown(result)
    assert "스피어만" in report and "요일 유형 순서" in report
