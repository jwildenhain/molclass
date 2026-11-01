import json
from app.v3_uploads import get_job


class FakeDB:
    def __init__(self, row):
        self.row = row

    def execute(self, query, params):
        assert params == {"job_id": 10}
        assert "MAX(se.job_event_id)" in str(query)
        assert "se.event_type='JOB_STARTED'" in str(query)
        return self

    def mappings(self):
        return self

    def first(self):
        return self.row


def test_running_progress_is_separate_from_final_result():
    progress = {"stage": "COMPUTE_FINGERPRINTS", "completed": 4, "total": 10, "percent": 64}
    job = get_job(10, FakeDB({"job_type": "MOLECULE_PREDICT_REQUEST", "status": "RUNNING", "progress": json.dumps(progress), "result": json.dumps(progress)}))
    assert job["progress"] == progress
    assert job["result"] is None


def test_success_is_the_only_state_that_reaches_one_hundred():
    job = get_job(10, FakeDB({"job_type": "MOLECULE_PREDICT_REQUEST", "status": "SUCCEEDED", "progress": None, "result": '{"moleculeId": 123}'}))
    assert job["progress"]["percent"] == 100
    assert job["result"] == {"moleculeId": 123}


def test_requeued_attempt_does_not_display_previous_attempt_progress():
    job = get_job(10, FakeDB({"job_type": "MOLECULE_PREDICT_REQUEST", "status": "QUEUED", "progress": '{"percent": 90}', "result": None}))
    assert job["progress"]["percent"] == 0
    assert job["progress"]["stage"] == "QUEUED"


def test_other_job_results_remain_unchanged():
    job = get_job(10, FakeDB({"job_type": "SDF_IMPORT", "status": "RUNNING", "progress": None, "result": '{"records": 5}'}))
    assert job["result"] == {"records": 5}
