"""기상 다변수 — weather_fit이 SKY(하늘상태)·TMP(기온)를 결합한다."""
from app.scoring.alternative import weather_fit


def test_weather_fit_uses_sky_and_temp_for_outdoor():
    # 맑고 쾌적(sky=1, tmp=24) 야외 → 높은 적합도
    hot = weather_fit(is_indoor=False, precip_prob=10, sky=1, tmp=36)   # 폭염
    mild = weather_fit(is_indoor=False, precip_prob=10, sky=1, tmp=24)
    assert mild > hot
    assert weather_fit(is_indoor=False, precip_prob=None) is None       # 결측 → None


def test_weather_fit_backward_compatible_without_multivars():
    # sky/tmp 미지정이면 기존 POP 단독 동작 그대로(회귀 0)
    assert weather_fit(is_indoor=False, precip_prob=10) == 0.9
    assert weather_fit(is_indoor=True, precip_prob=100) == 1.0
