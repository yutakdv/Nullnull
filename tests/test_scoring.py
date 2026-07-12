"""스코어링 산식 단위 테스트 — 기획서 9-1 ~ 9-4 원문 계수 검증."""
from datetime import date

import pytest

from app.scoring.alternative import (
    alternative_score,
    companion_fit,
    hidden_gem_score,
    jaccard,
    mobility_score,
    recommendation_load,
    theme_similarity,
    weather_fit,
)
from app.scoring.congestion import (
    calendar_weather_component,
    congestion_risk,
    label_of,
    level_of,
)
from app.scoring.course import course_score
from app.scoring.feedback_adjust import adjusted_risk, ewma_bias
from app.scoring.weights import renormalize

CR_WEIGHTS = {"concentration": 0.55, "region_visitor": 0.20,
              "demand": 0.15, "calendar_weather": 0.10}
ALT_WEIGHTS = {"theme": 0.30, "relief": 0.25, "mobility": 0.15,
               "hidden": 0.10, "weather": 0.10, "load_penalty": 0.10}
COURSE_WEIGHTS = {"move_penalty_per_10min": 0.02,
                  "category_repeat_penalty": 0.05, "dispersion_bonus": 0.05}


class TestLevels:
    @pytest.mark.parametrize("risk,expected", [
        (0, 1), (20, 1), (20.9, 2), (21, 2), (40, 2),
        (41, 3), (60, 3), (61, 4), (80, 4), (81, 5), (100, 5),
    ])
    def test_five_level_boundaries(self, risk, expected):
        assert level_of(risk) == expected

    def test_labels(self):
        assert label_of(10) == "매우 널널"
        assert label_of(90) == "매우 붐빔"


class TestRenormalize:
    def test_weights_sum_preserved_when_term_missing(self):
        # 날씨 항 결측 시 남은 가중치의 합이 1.0으로 유지된다(9-1)
        result = renormalize(CR_WEIGHTS, {"concentration", "region_visitor", "demand"})
        assert sum(result.values()) == pytest.approx(1.0)
        # 비율 유지: concentration : demand = 0.55 : 0.15
        assert result["concentration"] / result["demand"] == pytest.approx(0.55 / 0.15)

    def test_all_terms_present_identity(self):
        result = renormalize(CR_WEIGHTS, set(CR_WEIGHTS))
        assert result == pytest.approx(CR_WEIGHTS)


class TestCongestionRisk:
    def test_full_terms_weighted_sum(self):
        risk = congestion_risk(80, 60, 50, 70, CR_WEIGHTS)
        assert risk == pytest.approx(0.55 * 80 + 0.20 * 60 + 0.15 * 50 + 0.10 * 70, abs=0.1)

    def test_missing_terms_renormalized(self):
        # 집중률만 있으면 그대로 그 값
        assert congestion_risk(80, None, None, None, CR_WEIGHTS) == pytest.approx(80.0)

    def test_no_data_raises(self):
        with pytest.raises(ValueError):
            congestion_risk(None, None, None, None, CR_WEIGHTS)

    def test_calendar_component_weekend_holiday(self):
        sat = date(2026, 7, 11)
        mon = date(2026, 7, 13)
        holiday = date(2026, 8, 15)
        assert calendar_weather_component(sat) > calendar_weather_component(mon)
        assert calendar_weather_component(holiday, frozenset({holiday})) > \
            calendar_weather_component(mon)

    def test_rain_lowers_outdoor_only(self):
        d = date(2026, 7, 11)
        dry = calendar_weather_component(d, precip_prob=0)
        rainy = calendar_weather_component(d, precip_prob=80)
        indoor_rainy = calendar_weather_component(d, precip_prob=80, is_indoor=True)
        assert rainy < dry
        assert indoor_rainy == dry


class TestFeedbackAdjust:
    def test_ewma_converges_to_recent_direction(self):
        bias = ewma_bias([1] * 30)
        assert bias == pytest.approx(1.0, abs=0.01)
        assert ewma_bias([-1] * 30) == pytest.approx(-1.0, abs=0.01)

    def test_empty_feedback_zero_bias(self):
        assert ewma_bias([]) == 0.0

    def test_adjusted_risk_formula(self):
        # adjusted = risk × (1 + 0.2 × bias)
        assert adjusted_risk(70, 1.0, alpha=0.2) == pytest.approx(84.0)
        assert adjusted_risk(70, -1.0, alpha=0.2) == pytest.approx(56.0)
        assert adjusted_risk(70, 0.0, alpha=0.2) == pytest.approx(70.0)

    def test_adjusted_risk_clamped(self):
        assert adjusted_risk(95, 1.0) <= 100.0


class TestThemeSimilarity:
    def test_jaccard(self):
        assert jaccard({"A02", "A0201"}, {"A02", "A0201"}) == 1.0
        assert jaccard({"A02"}, {"A01"}) == 0.0

    def test_combined_formula(self):
        # 0.6×Jaccard + 0.4×related (9-2)
        sim = theme_similarity({"A02", "A0201", "A02010100"},
                               {"A02", "A0201", "A02010600"}, related_sim=0.8)
        expected = 0.6 * (2 / 4) + 0.4 * 0.8
        assert sim == pytest.approx(expected, abs=0.001)

    def test_related_missing_renormalizes_to_jaccard(self):
        sim = theme_similarity({"A02"}, {"A02"}, related_sim=None)
        assert sim == pytest.approx(1.0)

    def test_keyword_fallback(self):
        sim = theme_similarity(set(), set(), None,
                               tags_a=["역사", "포토스팟"], tags_b=["역사"])
        assert sim == pytest.approx(0.5)


class TestAlternativeScore:
    def test_cold_start_load_is_zero(self):
        # 로그가 없으면(max_raw=0) 페널티 0 — F8 graceful degradation(9-2)
        assert recommendation_load(0, 0, max_raw=0) == 0.0

    def test_load_formula_exposure_plus_double_selection(self):
        # 노출 10 + 선택 5×2 = 20 → max 20 기준 1.0
        assert recommendation_load(10, 5, max_raw=20) == 1.0
        assert recommendation_load(5, 0, max_raw=20) == 0.25

    def test_load_penalty_lowers_score(self):
        base = alternative_score(0.8, 0.5, 0.7, 0.6, 0.9, load=0.0, weights=ALT_WEIGHTS)
        loaded = alternative_score(0.8, 0.5, 0.7, 0.6, 0.9, load=1.0, weights=ALT_WEIGHTS)
        assert base - loaded == pytest.approx(0.10, abs=0.001)

    def test_weather_missing_renormalized(self):
        # 날씨 항 제외 시 남은 양의 항들이 0.90 총량을 유지
        score = alternative_score(1.0, 1.0, 1.0, 1.0, None, load=0.0, weights=ALT_WEIGHTS)
        assert score == pytest.approx(0.90, abs=0.001)

    def test_hidden_gem_cross(self):
        # 방문자 하위 × 콘텐츠 풍부도 상위 교차
        assert hidden_gem_score(0.9, 0.8) == pytest.approx(0.72)
        assert hidden_gem_score(0.9, 0.0) == 0.0

    def test_mobility_closer_is_better(self):
        assert mobility_score(10) > mobility_score(60)
        assert mobility_score(120) == 0.0

    def test_weather_fit(self):
        assert weather_fit(False, None) is None
        assert weather_fit(False, 80) < weather_fit(False, 10)
        assert weather_fit(True, 80) > weather_fit(False, 80)


class TestCourseScore:
    def test_formula(self):
        score = course_score([0.8, 0.6], total_move_min=40,
                             category_repeats=1, distinct_zones=3,
                             weights=COURSE_WEIGHTS)
        expected = 0.7 - 0.02 * 4 - 0.05 * 1 + 0.05 * 2
        assert score == pytest.approx(expected, abs=0.001)

    def test_empty_course(self):
        assert course_score([], 0, 0, 1, COURSE_WEIGHTS) == 0.0


class TestCompanionFit:
    def test_none_when_unset(self):
        assert companion_fit(None, low_percentile=0.5) is None
        assert companion_fit("", low_percentile=0.5) is None

    def test_solo_prefers_quiet(self):
        quiet = companion_fit("solo", low_percentile=0.9, tags=[])
        busy = companion_fit("solo", low_percentile=0.1, tags=[])
        assert quiet > busy

    def test_couple_prefers_photo_and_nature(self):
        assert companion_fit("couple", low_percentile=0.5, tags=["포토스팟"]) == 1.0
        assert companion_fit("couple", low_percentile=0.5, tags=["자연"]) == 1.0
        assert companion_fit("couple", low_percentile=0.5, tags=["미식"]) == 0.7
        assert companion_fit("couple", low_percentile=0.5, tags=["역사"]) == 0.5

    def test_family_prefers_indoor(self):
        indoor = companion_fit("family", low_percentile=0.5, tags=[], is_indoor=True)
        outdoor = companion_fit("family", low_percentile=0.5, tags=[], is_indoor=False)
        assert indoor > outdoor


class TestAlternativeScoreCompanion:
    def test_none_matches_baseline(self):
        # companion=None이면 항 자체가 빠져 기존 산식과 완전히 동일해야 한다(회귀 없음)
        base = alternative_score(0.8, 0.5, 0.7, 0.3, 0.6, 0.1, ALT_WEIGHTS)
        explicit = alternative_score(0.8, 0.5, 0.7, 0.3, 0.6, 0.1, ALT_WEIGHTS,
                                     companion=None)
        assert base == explicit

    def test_companion_shifts_ranking(self):
        # 동일 후보라도 companion 적합도가 높으면 점수가 오른다(소프트 우선정렬)
        low = alternative_score(0.8, 0.5, 0.7, 0.3, 0.6, 0.1, ALT_WEIGHTS,
                                companion=0.0)
        high = alternative_score(0.8, 0.5, 0.7, 0.3, 0.6, 0.1, ALT_WEIGHTS,
                                 companion=1.0)
        assert high > low
