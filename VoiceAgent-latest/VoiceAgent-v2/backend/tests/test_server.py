"""Regression tests: real ASGI routes, mocked upstream only; no paid API requests."""
import asyncio
import importlib
import json
import os
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch

# Synthetic test-only credentials; no user secrets are read.
os.environ['AGENT_WORKER_TOKEN'] = 'test-only-worker-token-' + 'x' * 32
os.environ['DEEPSEEK_API_KEY'] = 'test-only-key'
os.environ['DEEPSEEK_MODEL'] = 'test-model'
import httpx
from fastapi.testclient import TestClient
import server

AUTH = {'Authorization': 'Bearer ' + os.environ['AGENT_WORKER_TOKEN']}


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(server.app)
        with server.JOBS_LOCK:
            server.JOBS.clear()
            server.JOB_TIMES.clear()

    def tearDown(self):
        self.client.close()

    def test_health(self):
        self.assertEqual({'ok': True}, self.client.get('/health').json())

    def test_missing_and_default_secret_refuse_startup(self):
        for value in ['', 'change-me']:
            env = dict(os.environ, AGENT_WORKER_TOKEN=value, PYTHON_DOTENV_DISABLED='1')
            result = subprocess.run([sys.executable, '-c', 'import server'], env=env, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b'Set a random AGENT_WORKER_TOKEN', result.stderr)

    def test_authentication_required(self):
        for auth in [{}, {'Authorization': 'Bearer change-me'}, {'Authorization': os.environ['AGENT_WORKER_TOKEN']}]:
            self.assertEqual(401, self.client.post('/jobs', json={'kind': 'render', 'params_json': '{}'}, headers=auth).status_code)

    def test_no_fabricated_job_success(self):
        result = self.client.post('/jobs', json={'kind': 'render', 'params_json': '{}'}, headers=AUTH)
        self.assertEqual(200, result.status_code)
        key = result.json()['id']
        for _ in range(100):
            result = self.client.get('/jobs/' + key, headers=AUTH).json()
            if result['status'] == 'FAILED':
                break
            time.sleep(.01)
        self.assertEqual('FAILED', result['status'])
        self.assertIsNone(result['result_url'])

    def test_queue_is_bounded(self):
        held = []
        try:
            while server.JOB_SLOTS.acquire(False):
                held.append(True)
            result = self.client.post('/jobs', json={'kind': 'render', 'params_json': '{}'}, headers=AUTH)
            self.assertEqual(429, result.status_code)
            self.assertEqual(0, len(server.JOBS))
        finally:
            for _ in held: server.JOB_SLOTS.release()

    def test_retention_is_bounded(self):
        with patch.object(server, 'MAX_JOBS', 0):
            result = self.client.post('/jobs', json={'kind': 'render', 'params_json': '{}'}, headers=AUTH)
        self.assertEqual(429, result.status_code)

    def test_body_size_limit(self):
        result = self.client.post('/jobs', content=b'x' * (server.MAX_BODY_BYTES + 1), headers=AUTH)
        self.assertEqual(413, result.status_code)

    def test_parameter_size_limit(self):
        result = self.client.post('/jobs', json={'kind': 'x', 'params_json': 'x' * 32769}, headers=AUTH)
        self.assertEqual(422, result.status_code)

    def stream(self, body, status=200):
        transport = httpx.MockTransport(lambda request: httpx.Response(status, text=body))
        original = httpx.AsyncClient
        with patch.object(server.httpx, 'AsyncClient', side_effect=lambda **kwargs: original(transport=transport, **kwargs)):
            return self.client.post('/chat/stream', json={'messages': [{'role': 'user', 'content': 'hello'}]}, headers=AUTH)

    def test_sse_success_requires_done(self):
        response = self.stream('data: {"choices":[{"delta":{"content":"hello"}}]}\n\ndata: [DONE]\n\n')
        self.assertEqual(200, response.status_code)
        self.assertIn('"text": "hello"', response.text)
        self.assertEqual(1, response.text.count('[DONE]'))

    def test_clean_eof_is_not_success(self):
        response = self.stream('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n')
        self.assertIn('"error"', response.text)
        self.assertNotIn('data: [DONE]', response.text)

    def test_malformed_event_is_not_success(self):
        response = self.stream('data: broken-json\n\n')
        self.assertIn('"error"', response.text)
        self.assertNotIn('data: [DONE]', response.text)

    def test_upstream_error_does_not_leak_body(self):
        response = self.stream('private-upstream-details', 401)
        self.assertIn('HTTP 401', response.text)
        self.assertNotIn('private-upstream', response.text)
        self.assertNotIn('data: [DONE]', response.text)

    def test_model_must_be_configured(self):
        with patch.object(server, 'DEEPSEEK_MODEL', ''):
            response = self.client.post('/chat/stream', json={'messages': [{'role': 'user', 'content': 'hello'}]}, headers=AUTH)
        self.assertEqual(503, response.status_code)

    def test_stream_capacity_is_bounded(self):
        held = []
        try:
            while server.STREAM_SLOTS.acquire(False): held.append(True)
            response = self.client.post('/chat/stream', json={'messages': [{'role': 'user', 'content': 'hello'}]}, headers=AUTH)
            self.assertEqual(429, response.status_code)
        finally:
            for _ in held: server.STREAM_SLOTS.release()


if __name__ == '__main__':
    unittest.main()
