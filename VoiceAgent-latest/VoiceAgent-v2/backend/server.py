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
import json
import threading
import secrets
from concurrent.futures import ThreadPoolExecutor
from enum import Enum
from typing import Any, Dict, List, Optional

import httpx

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

# Подхватывает backend/.env при прямом запуске (uvicorn server:app --app-dir backend).
# При запуске через docker-compose эту же переменную подставляет сам compose
# (см. docker-compose.yml) — load_dotenv() тут не мешает, просто не находит
# файл внутри контейнера и молча ничего не делает.
load_dotenv()

WORKER_TOKEN = os.environ.get("AGENT_WORKER_TOKEN", "")
if len(WORKER_TOKEN) < 32 or WORKER_TOKEN in {"change-me", "replace-with-a-random-token-at-least-32-chars"}:
    raise RuntimeError("Set a random AGENT_WORKER_TOKEN of at least 32 characters before starting the worker")

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
    kind: str = Field(min_length=1, max_length=64)
    params_json: str = Field(max_length=32_768)


JOBS: Dict[str, Job3D] = {}
JOB_TIMES: Dict[str, float] = {}
JOBS_LOCK = threading.Lock()
MAX_JOBS = 128
JOB_TTL_SECONDS = 3600
JOB_SLOTS = threading.BoundedSemaphore(16)  # running + queued
EXECUTOR = ThreadPoolExecutor(max_workers=2, thread_name_prefix="voiceagent-job")
STREAM_SLOTS = threading.BoundedSemaphore(4)
MAX_BODY_BYTES = 128 * 1024


class BodyLimitMiddleware:
    """Bound bytes before JSON parsing, also for chunked requests without Content-Length."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        chunks = []
        total = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            total += len(chunk)
            if total > MAX_BODY_BYTES:
                await send({"type": "http.response.start", "status": 413, "headers": []})
                await send({"type": "http.response.body", "body": b"request too large"})
                return
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        sent = False
        async def replay():
            nonlocal sent
            if not sent:
                sent = True
                return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
            return await receive()
        await self.app(scope, replay, send)


app.add_middleware(BodyLimitMiddleware)


def require_auth(authorization: str = Header(default="")):
    token = authorization[7:] if authorization.startswith("Bearer ") else ""
    if not secrets.compare_digest(token.encode(), WORKER_TOKEN.encode()):
        raise HTTPException(status_code=401, detail="invalid worker token")


def run_job_stub(job_id: str):
    """Replace this with your real executor. Kept synchronous + backgrounded via thread
    for simplicity; swap for a proper task queue (RQ/Celery) for production use."""
    try:
        with JOBS_LOCK:
            job = JOBS[job_id]
            job.status = Job3DStatus.RUNNING
        # No real renderer is installed. Do not claim success or return a fabricated URL.
        with JOBS_LOCK:
            job.status = Job3DStatus.FAILED
            job.error_message = "Worker executor is not configured"
    finally:
        JOB_SLOTS.release()


@app.post("/jobs", response_model=Job3D)
def submit_job(req: SubmitJobRequest, authorization: str = Header(default="")):
    require_auth(authorization)
    if not JOB_SLOTS.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="job queue is full")
    try:
        with JOBS_LOCK:
            now = time.monotonic()
            expired = [key for key, job in JOBS.items()
                       if job.status in (Job3DStatus.DONE, Job3DStatus.FAILED)
                       and now - JOB_TIMES[key] > JOB_TTL_SECONDS]
            for key in expired:
                JOBS.pop(key, None)
                JOB_TIMES.pop(key, None)
            if len(JOBS) >= MAX_JOBS:
                raise HTTPException(status_code=429, detail="job retention capacity reached")
            job = Job3D(id=str(uuid.uuid4()), kind=req.kind, params_json=req.params_json)
            JOBS[job.id] = job
            JOB_TIMES[job.id] = now
        EXECUTOR.submit(run_job_stub, job.id)
        return job.model_copy(deep=True)
    except BaseException:
        JOB_SLOTS.release()
        raise


@app.get("/jobs/{job_id}", response_model=Job3D)
def get_job(job_id: str, authorization: str = Header(default="")):
    require_auth(authorization)
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        job = job.model_copy(deep=True) if job else None
    if job is None:
        raise HTTPException(status_code=404, detail="job not found")
    return job


DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
# Id модели задаётся через env: впиши DEEPSEEK_MODEL=<id DeepSeek v4 из их документации>, когда он есть в API.
DEEPSEEK_MODEL = os.environ.get("DEEPSEEK_MODEL", "")
# Модель для запросов со снимком экрана (текстовые модели картинки не принимают).
DEEPSEEK_VISION_MODEL = os.environ.get("DEEPSEEK_VISION_MODEL", "")


class ChatStreamRequest(BaseModel):
    messages: List[Dict[str, Any]] = Field(min_length=1, max_length=32)
    temperature: float = Field(default=0.7, ge=0, le=2)


def _sse(obj: dict) -> str:
    return f"data: {json.dumps(obj, ensure_ascii=False)}\n\n"


@app.post("/chat/stream")
async def chat_stream(req: ChatStreamRequest, authorization: str = Header(default="")):
    require_auth(authorization)
    api_key = os.environ.get("DEEPSEEK_API_KEY", "")
    if not api_key:
        raise HTTPException(status_code=500, detail="DEEPSEEK_API_KEY is not set")

    has_image = any(isinstance(m.get("content"), list) for m in req.messages)
    model = DEEPSEEK_VISION_MODEL if has_image else DEEPSEEK_MODEL
    if not model:
        raise HTTPException(status_code=503, detail="Configure a supported model ID")
    if not STREAM_SLOTS.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="stream capacity reached")

    async def generate_stream():
        payload = {"model": model, "messages": req.messages,
                   "temperature": req.temperature, "stream": True, "max_tokens": 8192}
        headers = {"Authorization": f"Bearer {api_key}"}
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(180.0, connect=10.0)) as client:
                async with client.stream("POST", DEEPSEEK_URL, json=payload, headers=headers) as r:
                    if r.status_code != 200:
                        yield _sse({"error": f"DeepSeek HTTP {r.status_code}"})
                        return
                    completed = False
                    async for line in r.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if data == "[DONE]":
                            completed = True
                            break
                        try:
                            piece = json.loads(data)["choices"][0]["delta"].get("content")
                        except (ValueError, KeyError, IndexError, TypeError):
                            yield _sse({"error": "invalid upstream SSE event"})
                            return
                        if piece:  # reasoning_content (мысли R1) намеренно не стримим
                            yield _sse({"text": piece})
                    if not completed:
                        yield _sse({"error": "upstream stream ended without [DONE]"})
                        return
            yield "data: [DONE]\n\n"
        except httpx.HTTPError:
            yield _sse({"error": "upstream network error"})
        finally:
            STREAM_SLOTS.release()

    return StreamingResponse(
        generate_stream(), media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.get("/health")
def health():
    return {"ok": True}
