import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field


class PairRequest(BaseModel):
    code: str = Field(pattern=r'^\d{6}$')
    name: str = Field(min_length=1, max_length=60, pattern=r'^[^\x00-\x1f]+$')


class Claim(BaseModel):
    id: str = Field(min_length=32, max_length=32)
    secret: str = Field(min_length=40, max_length=60)


class Command(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    mode: str = 'Ultra Free'
    request_id: str = Field(min_length=36, max_length=36)


class Decision(BaseModel):
    approve: bool


def create_app(engine, pairing):
    @asynccontextmanager
    async def lifespan(app):
        yield
        await engine.close()

    app = FastAPI(title='VanyaBridge local', docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    def owner(authorization):
        if not authorization or not authorization.startswith('Bearer '):
            raise HTTPException(401, 'Требуется сопряжение')
        did = engine.store.authenticate(authorization[7:])
        if not did:
            raise HTTPException(401, 'Устройство не авторизовано')
        return did

    def attempt(callback):
        try:
            return callback()
        except ValueError as error:
            raise HTTPException(400, str(error)) from None

    @app.middleware('http')
    async def limits(request, call_next):
        from starlette.responses import JSONResponse
        # Native clients only. Never enable permissive CORS or accept browser-origin mutations.
        if request.headers.get('origin'):
            return JSONResponse({'detail': 'Browser origin forbidden'}, status_code=403)
        length = request.headers.get('content-length', '')
        if request.method == 'POST' and (not length.isdigit() or int(length) > 8192):
            return JSONResponse({'detail': 'Body too large or missing length'}, status_code=413)
        return await call_next(request)

    @app.post('/pair/request')
    async def request_pair(body: PairRequest):
        return attempt(lambda: pairing.request(body.code, body.name))

    @app.post('/pair/claim')
    async def claim(body: Claim):
        return attempt(lambda: pairing.claim(body.id, body.secret))

    @app.get('/snapshot')
    async def snapshot(authorization: str | None = Header(default=None)):
        return engine.snapshot(owner(authorization))

    @app.post('/tasks')
    async def task(body: Command, authorization: str | None = Header(default=None)):
        did = owner(authorization)
        return attempt(lambda: engine.submit(did, body.text, body.mode, body.request_id))

    @app.post('/tasks/{tid}/approval')
    async def approval(tid: str, body: Decision, authorization: str | None = Header(default=None)):
        did = owner(authorization)
        attempt(lambda: engine.decide(tid, did, body.approve))
        return {'accepted': True}

    @app.post('/tasks/{tid}/cancel')
    async def cancel(tid: str, authorization: str | None = Header(default=None)):
        did = owner(authorization)
        attempt(lambda: engine.cancel(tid, did))
        return {'accepted': True}

    sockets = {}

    @app.websocket('/events')
    async def events(ws: WebSocket):
        token = ws.headers.get('authorization', '')
        try:
            did = owner(token)
        except HTTPException:
            await ws.close(code=4401)
            return
        if ws.headers.get('origin') or sockets.get(did, 0) >= 2:
            await ws.close(code=4403)
            return
        await ws.accept()
        sockets[did] = sockets.get(did, 0) + 1
        incoming = asyncio.create_task(ws.receive())
        previous = None
        try:
            while engine.store.authenticate(token[7:]) == did:
                current = engine.snapshot(did)
                if current != previous:
                    await ws.send_json(current)
                    previous = current
                try:
                    # Read disconnect frames too; a send-only loop can leak idle connections.
                    await asyncio.wait_for(asyncio.shield(incoming), timeout=1)
                    break
                except TimeoutError:
                    pass
            await ws.close(code=4401)
        except (WebSocketDisconnect, RuntimeError, OSError):
            pass
        finally:
            incoming.cancel()
            await asyncio.gather(incoming, return_exceptions=True)
            sockets[did] -= 1

    return app
