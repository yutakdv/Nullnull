"""A-064 (#337): the AI image declares no model provider - the half of the provider guard the infra test cannot see.

infra/test/staging.test.ts holds the task definition: no AI_PROVIDER other than NONE, no key and no model, and no env
file, command or entrypoint on any container the AI service runs. The container's environment also comes from the image,
and the infra gate builds with infra/ alone (infra/Dockerfile), so the image's ENV and ARG lines are read here instead."""

import shlex
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCKERFILE = ROOT / "apps/ai/Dockerfile"
PROVIDER_NAMES = {"AI_PROVIDER", "AI_API_KEY", "AI_MODEL_ID"}


def instructions(text):
    """The Dockerfile's instructions with continuation lines joined and comment lines dropped."""
    joined, pending = [], ""
    for raw in text.splitlines():
        line = raw.rstrip()
        if not pending and (not line.strip() or line.lstrip().startswith("#")):
            continue
        if line.endswith("\\"):
            pending += line[:-1] + " "
            continue
        joined.append((pending + line).strip())
        pending = ""
    if pending:
        joined.append(pending.strip())
    return joined


def declared_names(text):
    """The names ENV and ARG instructions set: ENV K=V K2=V2, the legacy ENV K V, ARG K and ARG K=V."""
    names = []
    for instruction in instructions(text):
        keyword, _, rest = instruction.partition(" ")
        if keyword.upper() not in ("ENV", "ARG"):
            continue
        tokens = shlex.split(rest)
        if keyword.upper() == "ENV" and tokens and "=" not in tokens[0]:
            names.append(tokens[0])
            continue
        names += [token.split("=", 1)[0] for token in tokens]
    return names


class AiImageProviderOffTests(unittest.TestCase):
    def test_the_ai_image_declares_no_model_provider(self):
        names = declared_names(DOCKERFILE.read_text(encoding="utf-8"))
        # The control: without it, "none of three names" also holds for a reader that found nothing at all.
        self.assertIn("NULLNULL_AI_PORT", names)
        self.assertEqual(set(), PROVIDER_NAMES & set(names))

    def test_the_reader_finds_a_provider_however_the_dockerfile_spells_it(self):
        for text in ("ENV AI_PROVIDER=OPENAI", 'ENV A=1 AI_API_KEY="x y"', "ENV AI_MODEL_ID gpt",
                     "ARG AI_PROVIDER", "ARG AI_API_KEY=unset", "ENV A=1 \\\n    AI_PROVIDER=OPENAI"):
            with self.subTest(text=text):
                self.assertTrue(PROVIDER_NAMES & set(declared_names(text)), text)


if __name__ == "__main__":
    unittest.main()
