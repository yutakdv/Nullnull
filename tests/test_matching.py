"""스팟 식별·매칭(app/matching.py) — 이름 정규화·외부 식별자 매핑."""
from app.matching import normalize_name


def test_normalize_name_strips_parens_space_punct():
    assert normalize_name("경복궁(사적 제117호)") == "경복궁"
    assert normalize_name(" 북촌 한옥마을 ") == "북촌한옥마을"
    assert normalize_name("N서울타워") == "n서울타워"
    assert normalize_name("덕수궁길·정동길") == "덕수궁길정동길"
    assert normalize_name("") == ""
