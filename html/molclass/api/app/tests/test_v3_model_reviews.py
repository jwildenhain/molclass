"""Focused regression tests for the read-only v3 model-review router."""

import os
import sys
from collections import deque
from typing import Any, Deque, Dict, Iterable, List, Optional, Tuple

import pytest
from fastapi import FastAPI, status
from fastapi.testclient import TestClient


sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))

from app import v3_model_reviews
from app.database import get_v3_db  # noqa: E402
from app.v3_model_reviews import router  # noqa: E402


class FakeRow:
    """SQLAlchemy-like row supporting mapping and attribute access."""

    def __init__(self, **values: Any):
        self._mapping = values

    def __getattr__(self, name: str) -> Any:
        try:
            return self._mapping[name]
        except KeyError as exc:
            raise AttributeError(name) from exc


class FakeResult:
    def __init__(
        self,
        first_row: Optional[FakeRow] = None,
        all_rows: Optional[Iterable[FakeRow]] = None,
    ):
        self._first_row = first_row
        self._all_rows = list(all_rows or [])

    def first(self) -> Optional[FakeRow]:
        return self._first_row

    def all(self) -> List[FakeRow]:
        return self._all_rows


class ScriptedSession:
    """Read-only session double returning results in execution order."""

    def __init__(self):
        self.results: Deque[FakeResult] = deque()
        self.executions: List[Tuple[str, Dict[str, Any]]] = []

    def queue(self, *results: FakeResult) -> None:
        self.results.extend(results)

    def execute(self, statement: Any, params: Optional[Dict[str, Any]] = None) -> FakeResult:
        sql = str(statement).strip()
        assert sql.lower().startswith("select"), "model-review routes must execute SELECT only"
        self.executions.append((sql, dict(params or {})))
        assert self.results, "unexpected database query"
        return self.results.popleft()

    def commit(self) -> None:
        raise AssertionError("model-review routes must not commit")

    def rollback(self) -> None:
        raise AssertionError("model-review routes must not roll back")

    def close(self) -> None:
        pass


@pytest.fixture
def fake_session() -> ScriptedSession:
    return ScriptedSession()


@pytest.fixture
def client(fake_session: ScriptedSession):
    test_app = FastAPI()
    test_app.include_router(router)

    def override_get_v3_db():
        yield fake_session

    test_app.dependency_overrides[get_v3_db] = override_get_v3_db
    with TestClient(test_app) as test_client:
        yield test_client
    test_app.dependency_overrides.clear()


def test_list_model_reviews_maps_latest_build_and_approval(
    client: TestClient, fake_session: ScriptedSession
) -> None:
    fake_session.queue(
        FakeResult(
            all_rows=[
                FakeRow(
                    model_definition_id=118,
                    model_name="Uncoupler classifier",
                    definition_status="READY",
                    algorithm_code="RandomForest",
                    feature_selection_code="NONE",
                    positive_class_label="uncoupler",
                    created_by="model-rebuilder",
                    created_at="2026-08-14T10:00:00",
                    updated_at="2026-08-14T11:00:00",
                    dataset_id=88,
                    dataset_name="uncoupler",
                    target_property="class",
                    profile_code="JUMBO",
                    model_build_id=31,
                    build_status="AWAITING_APPROVAL",
                    runstep="COMPLETE",
                    generation_number=1,
                    finished_at="2026-08-14T11:00:00",
                    published_at=None,
                    approval_status=None,
                    approved_by=None,
                    approved_at=None,
                ),
                FakeRow(
                    model_definition_id=119,
                    model_name="Definition without a build",
                    definition_status="DRAFT",
                    algorithm_code="IBk",
                    feature_selection_code="NONE",
                    positive_class_label="active",
                    created_by="operator",
                    created_at="2026-08-15T10:00:00",
                    updated_at="2026-08-15T11:00:00",
                    dataset_id=89,
                    dataset_name="pending",
                    target_property="class",
                    profile_code="ALL",
                    model_build_id=None,
                    build_status=None,
                    runstep=None,
                    generation_number=None,
                    finished_at=None,
                    published_at=None,
                    approval_status=None,
                    approved_by=None,
                    approved_at=None,
                ),
            ]
        )
    )

    response = client.get(
        "/api/v1/model-reviews",
        params={"status": "ready", "limit": 25, "offset": 5},
    )

    assert response.status_code == status.HTTP_200_OK
    body = response.json()
    assert body["returned"] == 2
    assert body["limit"] == 25
    assert body["offset"] == 5
    assert body["items"][0]["modelDefinitionId"] == 118
    assert body["items"][0]["dataset"] == {"datasetId": 88, "name": "uncoupler"}
    assert body["items"][0]["latestBuild"] == {
        "modelBuildId": 31,
        "status": "AWAITING_APPROVAL",
        "runstep": "COMPLETE",
        "generationNumber": 1,
        "finishedAt": "2026-08-14T11:00:00",
        "publishedAt": None,
    }
    assert body["items"][0]["approval"] is None
    assert body["items"][1]["latestBuild"] is None
    list_sql, list_params = fake_session.executions[0]
    assert list_params == {"status": "READY", "limit": 25, "offset": 5}
    assert "WHERE md.status = :status" in list_sql
    assert ":status IS NULL" not in list_sql
    assert "ORDER BY md.updated_at DESC, md.model_definition_id DESC" in list_sql
    assert "ORDER BY mb_latest.generation_number DESC" in list_sql
    assert "mb_latest.model_build_id DESC" not in list_sql
    assert not fake_session.results


def test_list_model_reviews_without_status_omits_status_predicate(
    client: TestClient, fake_session: ScriptedSession
) -> None:
    fake_session.queue(FakeResult(all_rows=[]))

    response = client.get("/api/v1/model-reviews")

    assert response.status_code == status.HTTP_200_OK
    assert response.json() == {
        "items": [],
        "limit": 100,
        "offset": 0,
        "returned": 0,
    }
    list_sql, list_params = fake_session.executions[0]
    assert list_params == {"limit": 100, "offset": 0}
    assert "WHERE md.status = :status" not in list_sql
    assert ":status IS NULL" not in list_sql
    assert "ORDER BY md.updated_at DESC, md.model_definition_id DESC" in list_sql
    assert not fake_session.results


def test_model_review_detail_returns_latest_build_evidence(
    client: TestClient, fake_session: ScriptedSession
) -> None:
    definition = FakeRow(
        model_definition_id=118,
        legacy_model_id=None,
        model_name="Uncoupler classifier",
        status="READY",
        algorithm_code="RandomForest",
        algorithm_options_json='{"trees": 500}',
        feature_selection_code="NONE",
        feature_selection_options_json=None,
        positive_class_label="uncoupler",
        declared_class_labels_json='["inactive", "uncoupler"]',
        published_model_build_id=None,
        created_by="model-rebuilder",
        definition_metadata_json='{"source": "migration"}',
        created_at="2026-08-14T10:00:00",
        updated_at="2026-08-14T11:00:00",
        dataset_id=88,
        dataset_name="uncoupler",
        dataset_status="READY",
        model_eligible=1,
        target_property_id=701,
        target_property="class",
        feature_profile_id=3,
        profile_code="JUMBO",
        profile_version=2,
        profile_status="ACTIVE",
    )
    latest_build = FakeRow(
        model_build_id=31,
        generation_label="v3-generation-1",
        generation_number=1,
        build_status="AWAITING_APPROVAL",
        runstep="COMPLETE",
        java_version="17",
        cdk_version="2.9",
        weka_version="3.8.6",
        code_revision="abc123",
        database_schema_version="V8",
        random_seed=20260814,
        split_strategy="STRATIFIED",
        split_configuration_json='{"training": 0.8}',
        training_count=1424,
        validation_count=177,
        holdout_count=177,
        excluded_count=0,
        build_manifest_json='{"records": 1778}',
        manifest_sha256="A1B2C3",
        error_code=None,
        error_message=None,
        build_created_at="2026-08-14T10:10:00",
        started_at="2026-08-14T10:11:00",
        finished_at="2026-08-14T11:00:00",
        published_at=None,
    )
    older_build = FakeRow(**dict(latest_build._mapping, model_build_id=30, generation_number=0))
    evaluation = FakeRow(
        evaluation_set="HOLDOUT",
        fold_number=None,
        class_label=None,
        metric_code="ACCURACY",
        metric_value=0.836158,
        support_count=177,
        metric_details_json='{"weighted": true}',
        created_at="2026-08-14T11:00:00",
    )
    artifact = FakeRow(
        model_artifact_id=51,
        artifact_kind="WEKA_MODEL",
        artifact_format="JAVA_SERIALIZED",
        media_type="application/octet-stream",
        artifact_size=4096,
        artifact_sha256="D4E5F6",
        created_at="2026-08-14T11:00:00",
    )
    approval = FakeRow(
        approval_status="PENDING",
        approved_by=None,
        approval_note=None,
        approved_at=None,
    )
    fake_session.queue(
        FakeResult(first_row=definition),
        FakeResult(all_rows=[latest_build, older_build]),
        FakeResult(all_rows=[evaluation]),
        FakeResult(all_rows=[artifact]),
        FakeResult(first_row=approval),
    )

    response = client.get("/api/v1/model-definitions/118/review")

    assert response.status_code == status.HTTP_200_OK
    body = response.json()
    assert body["definition"]["modelDefinitionId"] == 118
    assert body["definition"]["algorithm"] == {
        "code": "RandomForest",
        "options": {"trees": 500},
    }
    assert body["definition"]["declaredClassLabels"] == ["inactive", "uncoupler"]
    assert body["latestBuild"]["modelBuildId"] == 31
    assert body["latestBuild"]["manifest"] == {"records": 1778}
    assert [build["modelBuildId"] for build in body["builds"]] == [31, 30]
    assert body["evaluations"] == [
        {
            "evaluationSet": "HOLDOUT",
            "foldNumber": None,
            "classLabel": None,
            "metricCode": "ACCURACY",
            "metricValue": 0.836158,
            "supportCount": 177,
            "details": {"weighted": True},
            "createdAt": "2026-08-14T11:00:00",
        }
    ]
    assert body["artifacts"][0]["sha256"] == "D4E5F6"
    assert body["approval"] == {
        "status": "PENDING",
        "approvedBy": None,
        "note": None,
        "approvedAt": None,
    }
    assert body["approvalMutationAvailable"] is False
    assert len(fake_session.executions) == 5
    assert all(
        params == {"model_definition_id": 118}
        for _, params in fake_session.executions[:2]
    )
    assert all(
        params == {"model_build_id": 31}
        for _, params in fake_session.executions[2:]
    )
    build_sql = fake_session.executions[1][0]
    assert "ORDER BY mb.generation_number DESC" in build_sql
    assert "mb.model_build_id DESC" not in build_sql
    assert "artifact_blob" not in fake_session.executions[3][0].lower()
    assert not fake_session.results


def test_model_review_detail_returns_404_for_unknown_definition(
    client: TestClient, fake_session: ScriptedSession
) -> None:
    fake_session.queue(FakeResult(first_row=None))

    response = client.get("/api/v1/model-definitions/999/review")

    assert response.status_code == status.HTTP_404_NOT_FOUND
    assert response.json() == {"detail": "Model definition was not found."}
    assert len(fake_session.executions) == 1
    assert not fake_session.results



def test_model_review_router_exposes_guarded_decision_route(
    client: TestClient, monkeypatch
) -> None:
    monkeypatch.setattr(v3_model_reviews, "_approval_mutation_available", lambda: False)
    response = client.post(
        "/api/v1/model-builds/140/decision",
        json={
            "decision": "APPROVE",
            "reviewer": "release-reviewer",
            "note": "Reviewed model evidence.",
        },
    )
    assert response.status_code == status.HTTP_503_SERVICE_UNAVAILABLE


def test_model_decision_rejects_invalid_token(client: TestClient, monkeypatch) -> None:
    monkeypatch.setattr(v3_model_reviews, "_approval_mutation_available", lambda: True)
    monkeypatch.setattr(v3_model_reviews.settings, "model_review_token", "expected-token")

    response = client.post(
        "/api/v1/model-builds/140/decision",
        headers={"X-MolClass-Review-Token": "wrong-token"},
        json={
            "decision": "APPROVE",
            "reviewer": "release-reviewer",
            "note": "Reviewed model evidence.",
        },
    )

    assert response.status_code == status.HTTP_403_FORBIDDEN


def test_model_decision_invokes_canonical_approval(
    client: TestClient, monkeypatch
) -> None:
    captured = {}
    monkeypatch.setattr(v3_model_reviews, "_approval_mutation_available", lambda: True)
    monkeypatch.setattr(v3_model_reviews.settings, "model_review_token", "review-token")

    def fake_run_approval(model_build_id, decision, reviewer, note):
        captured.update(
            {
                "model_build_id": model_build_id,
                "decision": decision,
                "reviewer": reviewer,
                "note": note,
            }
        )
        return {
            "modelBuildId": model_build_id,
            "decision": decision,
            "buildStatus": "PUBLISHED",
            "reviewer": reviewer,
            "message": "completed",
        }

    monkeypatch.setattr(v3_model_reviews, "_run_approval", fake_run_approval)
    response = client.post(
        "/api/v1/model-builds/140/decision",
        headers={"X-MolClass-Review-Token": "review-token"},
        json={
            "decision": "approve",
            "reviewer": "release-reviewer",
            "note": "AUC and F1 reviewed.",
        },
    )

    assert response.status_code == status.HTTP_200_OK
    assert response.json()["buildStatus"] == "PUBLISHED"
    assert captured == {
        "model_build_id": 140,
        "decision": "APPROVE",
        "reviewer": "release-reviewer",
        "note": "AUC and F1 reviewed.",
    }


def _prepare_approval_settings(monkeypatch, tmp_path) -> None:
    wrapper = tmp_path / "gradlew"
    wrapper.write_text("#!/bin/sh\nexit 0\n")
    wrapper.chmod(0o755)
    monkeypatch.setattr(v3_model_reviews.settings, "approval_repo_root", tmp_path)
    monkeypatch.setattr(v3_model_reviews.settings, "approval_db_user", "molclass_model_approver")
    monkeypatch.setattr(v3_model_reviews.settings, "approval_db_pass", "approval-secret")
    monkeypatch.setattr(v3_model_reviews.settings, "v3_db_host", "127.0.0.1")
    monkeypatch.setattr(v3_model_reviews.settings, "v3_db_port", 3306)
    monkeypatch.setattr(v3_model_reviews.settings, "v3_db_name", "molclass_v3")
    monkeypatch.setattr(v3_model_reviews.settings, "model_approval_timeout_seconds", 120)


class _CompletedProcess:
    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = "") -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def test_run_approval_uses_named_java_cli_contract(monkeypatch, tmp_path) -> None:
    """V3ModelApproval.Config.parse only accepts named flags, never positional values."""
    _prepare_approval_settings(monkeypatch, tmp_path)
    captured: Dict[str, Any] = {}

    def fake_run(command, **keywords):
        captured["command"] = command
        captured["env"] = keywords["env"]
        captured["cwd"] = keywords["cwd"]
        return _CompletedProcess(stdout="Build 140 published by release-reviewer.")

    monkeypatch.setattr(v3_model_reviews.subprocess, "run", fake_run)

    result = v3_model_reviews._run_approval(
        140, "APPROVE", "release-reviewer", "AUC and F1 reviewed."
    )

    command = captured["command"]
    assert command[0] == str(tmp_path / "gradlew")
    assert "--no-daemon" in command
    assert ":approveV3Model" in command

    approval_property = next(
        part for part in command if part.startswith("-PapprovalArgs=")
    )
    approval_args = approval_property[len("-PapprovalArgs=") :]
    assert approval_args == (
        '--build-id 140 --decision APPROVE --actor "release-reviewer" '
        '--note "AUC and F1 reviewed."'
    )

    assert result["buildStatus"] == "PUBLISHED"
    assert captured["cwd"] == tmp_path


def test_run_approval_exports_environment_names_java_reads(monkeypatch, tmp_path) -> None:
    """V3ModelApproval reads MOLCLASS_JDBC_URL/DB_SCHEMA/DB_USER/DB_PASSWORD."""
    _prepare_approval_settings(monkeypatch, tmp_path)
    captured: Dict[str, Any] = {}

    def fake_run(command, **keywords):
        captured["env"] = keywords["env"]
        return _CompletedProcess()

    monkeypatch.setattr(v3_model_reviews.subprocess, "run", fake_run)
    v3_model_reviews._run_approval(140, "REJECT", "release-reviewer", "Insufficient evidence.")

    environment = captured["env"]
    assert environment["MOLCLASS_JDBC_URL"].startswith(
        "jdbc:mysql://127.0.0.1:3306/molclass_v3"
    )
    assert environment["MOLCLASS_DB_SCHEMA"] == "molclass_v3"
    assert environment["MOLCLASS_DB_USER"] == "molclass_model_approver"
    assert environment["MOLCLASS_DB_PASSWORD"] == "approval-secret"


def test_run_approval_maps_java_failure_to_conflict(monkeypatch, tmp_path) -> None:
    _prepare_approval_settings(monkeypatch, tmp_path)

    def fake_run(command, **keywords):
        return _CompletedProcess(
            returncode=2,
            stderr="Model approval failed: build 140 is PUBLISHED, expected AWAITING_APPROVAL",
        )

    monkeypatch.setattr(v3_model_reviews.subprocess, "run", fake_run)

    with pytest.raises(v3_model_reviews.HTTPException) as error:
        v3_model_reviews._run_approval(140, "APPROVE", "release-reviewer", "Reviewed.")

    assert error.value.status_code == status.HTTP_409_CONFLICT
    assert "expected AWAITING_APPROVAL" in error.value.detail


def test_run_approval_rejects_note_that_breaks_argument_quoting(
    monkeypatch, tmp_path
) -> None:
    _prepare_approval_settings(monkeypatch, tmp_path)

    def fail_run(command, **keywords):  # pragma: no cover - must never be reached
        raise AssertionError("the approval command must not start for unsafe input")

    monkeypatch.setattr(v3_model_reviews.subprocess, "run", fail_run)

    with pytest.raises(v3_model_reviews.HTTPException) as error:
        v3_model_reviews._run_approval(
            140, "APPROVE", "release-reviewer", 'note with " quote'
        )

    assert error.value.status_code == 422


def test_approval_failure_detail_strips_gradle_banner() -> None:
    """Reviewers must see the canonical Java reason, not the Gradle build banner."""
    output = (
        "Model approval failed: unknown model build 999999999\n"
        "\n"
        "FAILURE: Build failed with an exception.\n"
        "\n"
        "* What went wrong:\n"
        "Execution failed for task ':approveV3Model'.\n"
        "> Process 'command 'java'' finished with non-zero exit value 2\n"
    )

    assert (
        v3_model_reviews._approval_failure_detail(output)
        == "unknown model build 999999999"
    )


def test_approval_failure_detail_handles_missing_marker() -> None:
    assert (
        v3_model_reviews._approval_failure_detail("   ")
        == "The canonical model approval command failed without an error message"
    )
