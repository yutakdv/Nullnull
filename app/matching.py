"""외부 API 식별자 ↔ 스팟 매칭(집중률 tAtsNm / 서울 area명 / 연관 이름)."""
import re

_PARENS = re.compile(r"[(\[（【].*?[)\]）】]")
_NONWORD = re.compile(r"[\s·,\-_/.]+")


def normalize_name(name: str) -> str:
    """괄호 내용 제거 → 공백·구두점 제거 → 소문자화. '경복궁(사적)' → '경복궁'."""
    if not name:
        return ""
    text = _PARENS.sub("", name)
    text = _NONWORD.sub("", text)
    return text.strip().lower()
