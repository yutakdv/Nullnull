"""OpenAI 코스 래퍼 — 키 없으면 비활성·None 반환으로 폴백을 보장한다(Task B1).

테스트 환경엔 OPENAI_API_KEY가 없다.
"""


def test_llm_disabled_without_key():
    from app.external import openai_api
    assert openai_api.is_llm_enabled() is False


def test_complete_courses_returns_none_when_disabled():
    from app.external import openai_api
    assert openai_api.complete_courses({"conditions": {}, "candidates": []}) is None
