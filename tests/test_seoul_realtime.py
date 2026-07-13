"""서울 실시간 도시데이터 파싱(_parse_ppltn_row) — 인원·비상주율·예측 필드 확장."""
from app.external import seoul_api


SAMPLE_ROW = {
    "AREA_CONGEST_LVL": "붐빔", "AREA_CONGEST_MSG": "사람이 많아 붐빕니다",
    "AREA_PPLTN_MIN": "42000", "AREA_PPLTN_MAX": "44000",
    "NON_RESNT_PPLTN_RATE": "63.5", "PPLTN_TIME": "2026-07-13 15:00",
    "FCST_PPLTN": [
        {"FCST_TIME": "2026-07-13 16:00", "FCST_CONGEST_LVL": "약간 붐빔",
         "FCST_PPLTN_MIN": "30000", "FCST_PPLTN_MAX": "32000"},
    ],
}


def test_parse_ppltn_row_extracts_all_fields():
    out = seoul_api._parse_ppltn_row(SAMPLE_ROW)
    assert out["level_label"] == "붐빔"
    assert out["ppltn_min"] == 42000 and out["ppltn_max"] == 44000
    assert out["non_resident_rate"] == 63.5
    assert out["congest_msg"].startswith("사람이 많아")
    assert out["forecast"][0]["hour"] == "16"
    assert out["forecast"][0]["ppltn_max"] == 32000
