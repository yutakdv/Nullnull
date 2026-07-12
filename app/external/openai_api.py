"""OpenAI 기반 AI 코스 구성 — 알고리즘이 추린 후보로 최적 코스를 짜게 한다.

키/네트워크가 없으면 None을 돌려 알고리즘 다중 코스 폴백을 트리거한다(오프라인 심사 대비).
LLM은 반드시 주어진 후보의 spot_id만 쓰게 하고, 반환값은 서비스 계층에서 다시 검증한다.
"""
import json

from app.config import get_settings

_SYSTEM = (
    "너는 '널널(Nullnull)' 여행 큐레이터다. 사용자 조건과 후보 관광지 목록(candidates)을 받아 "
    "붐빔(risk)이 낮고 이동 동선이 짧으며 날씨·실내외 선호에 맞는 여행 코스를 여러 개 구성한다.\n"
    "규칙:\n"
    "1) 반드시 candidates에 있는 spot_id만 사용한다. 새 장소를 지어내지 않는다.\n"
    "2) 한 코스 안에서 같은 spot_id를 반복하지 않는다.\n"
    "3) 각 코스는 서로 다른 성격(예: 여유 산책형 / 실내 위주 / 포토스팟 중심)을 갖게 한다.\n"
    "4) risk가 낮은(널널한) 장소를 우선하고, 좌표가 가까운 순서로 동선을 정한다.\n"
    "5) 아래 JSON 스키마만 출력한다. 다른 텍스트를 덧붙이지 않는다.\n"
    '{"courses":[{"title":str,"concept":str,"reason":str,'
    '"stops":[{"spot_id":int,"note":str}]}]}'
)


def is_llm_enabled() -> bool:
    """OpenAI 키가 설정돼 있으면 True."""
    return bool(get_settings().openai_api_key)


def complete_courses(prompt_payload: dict) -> dict | None:
    """후보·조건 payload를 넘겨 코스 JSON을 받는다. 실패·비활성 시 None."""
    settings = get_settings()
    if not settings.openai_api_key:
        return None
    try:
        from openai import OpenAI

        client = OpenAI(api_key=settings.openai_api_key, timeout=25)
        resp = client.chat.completions.create(
            model=settings.openai_model,
            response_format={"type": "json_object"},
            temperature=0.7,
            messages=[
                {"role": "system", "content": _SYSTEM},
                {"role": "user", "content": json.dumps(prompt_payload, ensure_ascii=False)},
            ],
        )
        return json.loads(resp.choices[0].message.content)
    except Exception:
        # 네트워크·쿼터·파싱 오류는 모두 폴백으로 흡수(데모 안정성 우선)
        return None
