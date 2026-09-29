"""
Minimal job queue backend for VoiceAgent's ThreeDOrchestrator / RemoteWorkerClient.
Runs on a machine with real compute (GPU workstation, VPS) at AGENT_WORKER_URL.
The phone submits jobs, this service (or a worker process reading from the same
store) executes them and updates status; the phone polls GET /jobs/{id}.

This is intentionally a thin reference implementation — plug in your own executor
(Blender headless render, a game-streaming session, etc.) inside `run_job_stub`.
"""
import os
import uuid
import time
import threading
from enum import Enum
from typing import Dict, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

# Подхватывает backend/.env при прямом запуске (uvicorn server:app --app-dir backend).
# При запуске через docker-compose эту же переменную подставляет сам compose
# (см. docker-compose.yml) — load_dotenv() тут не мешает, просто не находит
# файл внутри контейнера и молча ничего не делает.
load_dotenv()

WORKER_TOKEN = os.environ.get("AGENT_WORKER_TOKEN", "change-me")
if WORKER_TOKEN == "change-me":
    print(
        "[VoiceAgent Worker] ВНИМАНИЕ: AGENT_WORKER_TOKEN не задан (или backend/.env "
        "не найден/не подхватился) — используется значение по умолчанию 'change-me'. "
        "Скопируй backend/.env.example в backend/.env и впиши свой токен."
    )

app = FastAPI(title="VoiceAgent Worker")


class Job3DStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    DONE = "DONE"
    FAILED = "FAILED"


class Job3D(BaseModel):
    id: str
    kind: str
    params_json: str
    status: Job3DStatus = Job3DStatus.QUEUED
    result_url: Optional[str] = None
    error_message: Optional[str] = None


class SubmitJobRequest(BaseModel):
    kind: str
    params_json: str


JOBS: Dict[str, Job3D] = {}


def require_auth(authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    if token != WORKER_TOKEN:
        raise HTTPException(status_code=401, detail="invalid worker token")


def run_job_stub(job_id: str):
    """Replace this with your real executor. Kept synchronous + backgrounded via thread
    for simplicity; swap for a proper task queue (RQ/Celery) for production use."""
    job = JOBS[job_id]
    job.status = Job3DStatus.RUNNING
    time.sleep(5)  # simulate work
    job.status = Job3DStatus.DONE
    job.result_url = f"https://your-vps.example.com/results/{job_id}.png"


@app.post("/jobs", response_model=Job3D)
def submit_job(req: SubmitJobRequest, authorization: str = Header(default="")):
    require_auth(authorization)
    job = Job3D(id=str(uuid.uuid4()), kind=req.kind, params_json=req.params_json)
    JOBS[job.id] = job
    threading.Thread(target=run_job_stub, args=(job.id,), daemon=True).start()
    return job


@app.get("/jobs/{job_id}", response_model=Job3D)
def get_job(job_id: str, authorization: str = Header(default="")):
    require_auth(authorization)
    job = JOBS.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="job not found")
    return job


@app.get("/health")
def health():
    return {"ok": True}
