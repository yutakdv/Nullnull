"""scripts/aws/oidc-negative-probe.sh (BA-006-T3): only a refusal next to an accepted control is a verdict."""
import base64
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

PROBE = Path(__file__).resolve().parents[2] / 'scripts' / 'aws' / 'oidc-negative-probe.sh'

FAKE_CURL = """#!/usr/bin/env bash
printf '{"value":"%s"}' "$FAKE_TOKEN"
"""
# Answers per role from FAKE_<ROLE> (accept | deny | expired); never prints a credential.
FAKE_AWS = """#!/usr/bin/env bash
role=""
while [[ $# -gt 0 ]]; do [[ "$1" == --role-arn ]] && role="${2##*/}"; shift; done
key="FAKE_$(tr 'a-z-' 'A-Z_' <<<"$role")"
case "${!key}" in
  accept) echo AROAEXAMPLE:session ;;
  deny) echo "An error occurred (AccessDenied) when calling the AssumeRoleWithWebIdentity operation: Not authorized" >&2; exit 254 ;;
  *) echo "An error occurred (ExpiredTokenException) when calling the AssumeRoleWithWebIdentity operation" >&2; exit 254 ;;
esac
"""


def token(environment):
    payload = base64.urlsafe_b64encode(json.dumps(
        {'sub': f'repo:o@1/r@2:environment:{environment}'}).encode()).decode().rstrip('=')
    return f'e30.{payload}.sig'


class OidcNegativeProbe(unittest.TestCase):
    def run_probe(self, own, other, environment='staging-build'):
        with tempfile.TemporaryDirectory() as d:
            for name, body in [('curl', FAKE_CURL), ('aws', FAKE_AWS)]:
                p = Path(d) / name
                p.write_text(body)
                p.chmod(0o755)
            env = {**os.environ, 'PATH': f"{d}:{os.environ['PATH']}",
                   'ACTIONS_ID_TOKEN_REQUEST_URL': 'https://example.invalid/?x=1',
                   'ACTIONS_ID_TOKEN_REQUEST_TOKEN': 'request-token', 'ACCOUNT': '000000000000',
                   'OWN_ROLE': 'nullnull-stg-github-publish', 'OTHER_ROLE': 'nullnull-stg-github-deploy',
                   'ENVIRONMENT': 'staging-build', 'FAKE_TOKEN': token(environment),
                   'FAKE_NULLNULL_STG_GITHUB_PUBLISH': own, 'FAKE_NULLNULL_STG_GITHUB_DEPLOY': other}
            result = subprocess.run(['bash', str(PROBE)], env=env, capture_output=True, text=True)
        return result.returncode, result.stdout

    def test_refusal_next_to_an_accepted_control_is_the_verdict(self):
        code, out = self.run_probe('accept', 'deny')
        self.assertEqual(0, code, out)
        self.assertIn('oidc_control=accepted role=nullnull-stg-github-publish', out)
        self.assertIn('oidc_negative=rejected role=nullnull-stg-github-deploy subject_environment=staging-build', out)

    def test_the_other_role_accepting_the_token_fails(self):
        code, out = self.run_probe('accept', 'accept')
        self.assertEqual(1, code)
        self.assertIn('oidc_negative=ACCEPTED', out)

    def test_an_error_that_is_not_access_denied_is_no_verdict(self):
        code, out = self.run_probe('accept', 'expired')
        self.assertEqual(1, code)
        self.assertIn('oidc_probe=no-verdict reason=nullnull-stg-github-deploy-other:ExpiredTokenException', out)
        self.assertNotIn('oidc_negative=rejected', out)

    def test_a_refused_control_makes_the_refusal_meaningless(self):
        code, out = self.run_probe('deny', 'deny')
        self.assertEqual(1, code)
        self.assertIn('reason=control-role-nullnull-stg-github-publish-denied', out)
        self.assertNotIn('oidc_negative=rejected', out)

    def test_a_token_from_another_environment_is_no_verdict(self):
        code, out = self.run_probe('accept', 'deny', environment='staging')
        self.assertEqual(1, code)
        self.assertIn('reason=token-not-from-environment-staging-build', out)


if __name__ == '__main__':
    unittest.main()
