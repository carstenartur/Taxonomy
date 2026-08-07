#!/usr/bin/env python3
"""Apply the fail-closed Render delivery contract from issue #618."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DELIVERY = ROOT / ".github" / "workflows" / "delivery.yml"
VERIFY = ROOT / ".github" / "scripts" / "verify-deployment.py"
VERIFY_TEST = ROOT / ".github" / "scripts" / "test-verify-deployment.py"
HARDENING = ROOT / ".github" / "scripts" / "check-delivery-hardening.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch_delivery(source: str) -> str:
    marker = "\n  deploy-render:\n"
    if source.count(marker) != 1:
        raise RuntimeError("delivery.yml: expected one deploy-render job")
    prefix = source.split(marker, 1)[0]
    replacement = r'''

  render-deployment-disabled:
    name: Render deployment disabled
    needs: publish-image
    if: >-
      github.event.workflow_run.head_branch == github.event.repository.default_branch &&
      github.event.workflow_run.event == 'push' &&
      vars.RENDER_DEPLOY_ENABLED != 'true'
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - name: Record explicitly disabled Render delivery
        env:
          BASE_URL: ${{ vars.RENDER_BASE_URL || 'https://taxonomy-analyzer.onrender.com' }}
          EXPECTED_SHA: ${{ github.event.workflow_run.head_sha }}
        shell: bash
        run: |
          set -euo pipefail
          mkdir -p target/delivery-evidence
          python3 - <<'PY'
          from datetime import datetime, timezone
          import json
          import os
          from pathlib import Path

          now = datetime.now(timezone.utc).isoformat(timespec="seconds")
          evidence = {
              "schemaVersion": 2,
              "deploymentState": "disabled",
              "result": "disabled",
              "targetUrl": os.environ["BASE_URL"].rstrip("/"),
              "expectedCommit": os.environ["EXPECTED_SHA"],
              "startedAt": now,
              "endedAt": now,
              "detail": "Render deployment is explicitly disabled by RENDER_DEPLOY_ENABLED.",
          }
          path = Path("target/delivery-evidence/render-disabled.json")
          path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
          PY
          echo "Render deployment is explicitly disabled; no rollout-success check was emitted."

      - name: Archive disabled Render delivery evidence
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: render-deployment-disabled-${{ github.event.workflow_run.head_sha }}
          path: target/delivery-evidence/render-disabled.json
          if-no-files-found: error
          retention-days: 90

  deploy-render:
    name: Deploy to Render
    needs: publish-image
    if: >-
      github.event.workflow_run.head_branch == github.event.repository.default_branch &&
      github.event.workflow_run.event == 'push' &&
      vars.RENDER_DEPLOY_ENABLED == 'true'
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - name: Checkout verified delivery source
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          ref: ${{ github.event.workflow_run.head_sha }}
          persist-credentials: false

      - name: Trigger and verify Render deployment
        env:
          HOOK_URL: ${{ secrets.RENDER_DEPLOY_HOOK_URL }}
          RENDER_API_KEY: ${{ secrets.RENDER_API_KEY }}
          CONFIGURED_RENDER_SERVICE_ID: ${{ vars.RENDER_SERVICE_ID }}
          BASE_URL: ${{ vars.RENDER_BASE_URL || 'https://taxonomy-analyzer.onrender.com' }}
          EXPECTED_SHA: ${{ github.event.workflow_run.head_sha }}
        shell: bash
        run: |
          set -euo pipefail
          mkdir -p target/delivery-evidence
          if [[ -z "$HOOK_URL" ]]; then
            python3 - <<'PY'
          from datetime import datetime, timezone
          import json
          import os
          from pathlib import Path

          now = datetime.now(timezone.utc).isoformat(timespec="seconds")
          evidence = {
              "schemaVersion": 2,
              "deploymentState": "failed",
              "result": "failure",
              "targetUrl": os.environ["BASE_URL"].rstrip("/"),
              "expectedCommit": os.environ["EXPECTED_SHA"],
              "startedAt": now,
              "endedAt": now,
              "detail": "RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing.",
          }
          Path("target/delivery-evidence/render-configuration-failure.json").write_text(
              json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
          PY
            echo "::error::RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing."
            exit 1
          fi

          deploy_url=$(python3 - <<'PY'
          import os
          from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

          url = os.environ["HOOK_URL"]
          expected = os.environ["EXPECTED_SHA"]
          parts = urlsplit(url)
          query = dict(parse_qsl(parts.query, keep_blank_values=True))
          query["ref"] = expected
          print(urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment)))
          PY
          )
          curl --fail --silent --show-error --retry 3 \
            --output target/delivery-evidence/render-deploy-hook.json \
            "$deploy_url"

          python3 - <<'PY'
          from datetime import datetime, timezone
          import json
          import os
          from pathlib import Path

          now = datetime.now(timezone.utc).isoformat(timespec="seconds")
          evidence = {
              "schemaVersion": 2,
              "deploymentState": "triggered",
              "result": "in_progress",
              "targetUrl": os.environ["BASE_URL"].rstrip("/"),
              "expectedCommit": os.environ["EXPECTED_SHA"],
              "startedAt": now,
              "detail": "Render deploy hook accepted the verified commit; live verification follows.",
          }
          Path("target/delivery-evidence/render-triggered.json").write_text(
              json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
          PY

          render_service_id="$CONFIGURED_RENDER_SERVICE_ID"
          if [[ -z "$render_service_id" ]]; then
            render_service_id=$(python3 - <<'PY'
          import os
          import re
          from urllib.parse import urlsplit

          match = re.search(r"(?:^|/)(srv-[A-Za-z0-9]+)(?:/|$)", urlsplit(os.environ["HOOK_URL"]).path)
          print(match.group(1) if match else "")
          PY
          )
          fi

          verify_args=(
            --base-url "$BASE_URL"
            --expected-commit "$EXPECTED_SHA"
            --deploy-response-file target/delivery-evidence/render-deploy-hook.json
            --evidence-file target/delivery-evidence/render-verification.json
          )
          if [[ -n "$RENDER_API_KEY" && -n "$render_service_id" ]]; then
            verify_args+=(
              --render-api-key "$RENDER_API_KEY"
              --render-service-id "$render_service_id"
            )
            echo "Render API deploy-status verification enabled."
          else
            echo "Render API credentials/service ID not configured; live readiness and commit verification remain authoritative."
          fi

          echo "Render deployment triggered for the verified commit; waiting for readiness."
          python3 .github/scripts/verify-deployment.py "${verify_args[@]}"

      - name: Archive Render deployment evidence
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: render-deployment-evidence-${{ github.event.workflow_run.head_sha }}
          path: target/delivery-evidence/**
          if-no-files-found: error
          retention-days: 90
'''
    return prefix + replacement


def patch_verify(source: str) -> str:
    source = replace_once(
        source,
        '''        "schemaVersion": 1,
        "targetUrl": args.base_url.rstrip("/"),
''',
        '''        "schemaVersion": 2,
        "deploymentState": "verifying",
        "targetUrl": args.base_url.rstrip("/"),
''',
        "verification evidence schema and state",
    )
    source = replace_once(
        source,
        '''        "result": "in_progress",
    }

    for attempt in range(1, attempts + 1):
''',
        '''        "result": "in_progress",
    }
    write_evidence(args.evidence_file, evidence)

    for attempt in range(1, attempts + 1):
''',
        "persist verifying evidence",
    )
    source = replace_once(
        source,
        '''                            "endedAt": utc_now(),
                            "result": "failure",
                            "detail": last_error,
''',
        '''                            "endedAt": utc_now(),
                            "deploymentState": "failed",
                            "result": "failure",
                            "detail": last_error,
''',
        "platform failure state",
    )
    source = replace_once(
        source,
        '''                        "endedAt": utc_now(),
                        "result": "success",
                        "detail": detail,
''',
        '''                        "endedAt": utc_now(),
                        "deploymentState": "succeeded",
                        "result": "success",
                        "detail": detail,
''',
        "success state",
    )
    source = replace_once(
        source,
        '''            "endedAt": utc_now(),
            "result": "failure",
            "detail": last_error,
''',
        '''            "endedAt": utc_now(),
            "deploymentState": "failed",
            "result": "failure",
            "detail": last_error,
''',
        "terminal failure state",
    )
    return source


def patch_test(source: str) -> str:
    insertion = '''
    def test_verify_once_rejects_readiness_failure(self) -> None:
        def fetcher(url: str):
            if url.endswith("/readiness"):
                return {"status": "DOWN"}
            return {"git": {"commit": {"id": "0123456789abcdef"}}}

        ok, detail = MODULE.verify_once(
            "https://example.invalid",
            "0123456789abcdef",
            fetcher,
            lambda _url: 200,
        )
        self.assertFalse(ok)
        self.assertIn("readiness is not UP", detail)

    def test_explicit_delivery_state_vocabulary_is_stable(self) -> None:
        expected = {"disabled", "triggered", "verifying", "succeeded", "failed"}
        workflow = (SCRIPT.parents[1] / "workflows" / "delivery.yml").read_text(
            encoding="utf-8"
        )
        verifier = SCRIPT.read_text(encoding="utf-8")
        for state in expected:
            with self.subTest(state=state):
                self.assertIn(f'"deploymentState": "{state}"', workflow + verifier)

'''
    marker = '\n\nif __name__ == "__main__":\n'
    if source.count(marker) != 1:
        raise RuntimeError("test-verify-deployment.py: main marker not unique")
    return source.replace(marker, "\n" + insertion + marker, 1)


def patch_hardening(source: str) -> str:
    source = replace_once(
        source,
        '''        "RENDER_SERVICE_ID",
        "BASE_URL: ${{ vars.RENDER_BASE_URL || 'https://taxonomy-analyzer.onrender.com' }}",
        "python3 .github/scripts/verify-deployment.py",
''',
        '''        "RENDER_SERVICE_ID",
        "RENDER_DEPLOY_ENABLED",
        "name: Render deployment disabled",
        "vars.RENDER_DEPLOY_ENABLED != 'true'",
        "vars.RENDER_DEPLOY_ENABLED == 'true'",
        '"deploymentState": "disabled"',
        '"deploymentState": "triggered"',
        "RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing",
        "BASE_URL: ${{ vars.RENDER_BASE_URL || 'https://taxonomy-analyzer.onrender.com' }}",
        "python3 .github/scripts/verify-deployment.py",
''',
        "delivery truth requirements",
    )
    source = replace_once(
        source,
        '''    if "keep_files: true" in delivery:
        failures.append("delivery.yml must replace the report tree atomically, not retain stale files")
''',
        '''    if "keep_files: true" in delivery:
        failures.append("delivery.yml must replace the report tree atomically, not retain stale files")
    if "RENDER_DEPLOY_HOOK_URL is not configured; Render deployment is disabled." in delivery:
        failures.append("enabled Render delivery must fail closed when its hook secret is missing")
    if 'if-no-files-found: warn' in delivery.split("  deploy-render:", 1)[-1]:
        failures.append("Render delivery evidence must never be optional")
''',
        "delivery negative contracts",
    )
    source = replace_once(
        source,
        '''        "renderDeployStatus",
        "root smoke test",
        "write_evidence",
''',
        '''        "renderDeployStatus",
        '"deploymentState": "verifying"',
        '"deploymentState": "succeeded"',
        '"deploymentState": "failed"',
        "root smoke test",
        "write_evidence(args.evidence_file, evidence)",
''',
        "verification state requirements",
    )
    source = replace_once(
        source,
        '''        "records deployment evidence and can poll platform status; responsive tree height remains "
''',
        '''        "records explicit disabled/triggered/verifying/succeeded/failed evidence and can poll platform status; responsive tree height remains "
''',
        "success summary",
    )
    return source


def main() -> None:
    delivery = DELIVERY.read_text(encoding="utf-8")
    if "render-deployment-disabled:" in delivery:
        print("Render delivery truth contract already applied.")
        return
    DELIVERY.write_text(patch_delivery(delivery), encoding="utf-8")
    VERIFY.write_text(patch_verify(VERIFY.read_text(encoding="utf-8")), encoding="utf-8")
    VERIFY_TEST.write_text(patch_test(VERIFY_TEST.read_text(encoding="utf-8")), encoding="utf-8")
    HARDENING.write_text(patch_hardening(HARDENING.read_text(encoding="utf-8")), encoding="utf-8")
    print("Applied truthful Render delivery contract.")


if __name__ == "__main__":
    main()
