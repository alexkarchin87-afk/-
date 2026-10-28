import asyncio
import os
import queue
import threading
import traceback
from pathlib import Path

import uvicorn

from backend.agents.browser import BrowserAgent
from backend.agents.local_llm import LocalLLM
from backend.agents.system import SystemAgent
from backend.api import create_app
from backend.engine import Engine
from backend.security.identity import certificate, validate_host
from backend.security.pairing import Pairing
from backend.store import Store


def default_data():
    return Path(os.environ.get('LOCALAPPDATA', str(Path.home() / '.local' / 'share'))) / 'VanyaBridge'


class Runtime:
    """All server state stays on one asyncio thread; tkinter communicates via a queue."""
    def __init__(self, host='127.0.0.1', port=8765, data=None):
        self.host, self.port = validate_host(host), port
        self.data = Path(data) if data else default_data()
        self.cert, self.key, self.fingerprint = certificate(self.data)
        self.root = self.data / 'TestFolder'
        self.root.mkdir(exist_ok=True)
        self.ready = threading.Event()
        self.messages = queue.Queue()
        self.thread = threading.Thread(target=self._thread, daemon=True)
        self.failure = None

    def start(self):
        self.thread.start()
        if not self.ready.wait(10):
            raise RuntimeError('Сервер не запустился')
        if self.failure:
            raise RuntimeError(self.failure)

    def _thread(self):
        try:
            asyncio.run(self._serve())
        except BaseException as error:
            self.failure = type(error).__name__
            self.failure_detail = traceback.format_exc()
            self.ready.set()
            self.messages.put(('error', 'Сервер остановлен: ' + self.failure))

    async def _serve(self):
        self.loop = asyncio.get_running_loop()
        self.store = Store(self.data / 'history.db')
        try:
            self.engine = Engine(self.store, BrowserAgent(self.root), SystemAgent(self.root))
            self.pairing = Pairing(self.store)
            app = create_app(self.engine, self.pairing)
            config = uvicorn.Config(app, host=self.host, port=self.port, ssl_certfile=str(self.cert),
                                    ssl_keyfile=str(self.key), access_log=False, log_level='warning',
                                    log_config=None,  # Windowed EXE has no stdout/stderr.
                                    ws='websockets', ws_max_size=8192, limit_concurrency=32,
                                    timeout_keep_alive=5)
            self.server = uvicorn.Server(config)
            running = asyncio.create_task(self.server.serve())
            while not self.server.started:
                if running.done():
                    await running
                    raise RuntimeError('Не удалось привязать адрес сервера')
                await asyncio.sleep(.05)
            self.ready.set()
            await running
        finally:
            if hasattr(self, 'engine'):
                await self.engine.close()
            self.store.close()

    def call(self, action, *args):
        async def dispatch():
            try:
                if action == 'snapshot':
                    value = self.engine.snapshot()
                    value.update(pending=self.pairing.list_pending(), devices=self.store.devices(), logs=self.store.logs(), chats=self.store.chat_snapshot())
                elif action == 'set_llm':
                    model = args[0].strip()
                    self.engine.local_llm = LocalLLM(model) if model else None
                    value = model
                elif action == 'new_chat':
                    value = self.store.new_chat()
                elif action == 'chat_submit':
                    chat_id, text, mode, rid = args
                    value = self.engine.submit('desktop', text, mode, rid, chat_id)
                    self.store.title_chat(chat_id, text)
                elif action == 'pair_code':
                    value = self.pairing.rotate()
                elif action == 'pair_decide':
                    value = self.pairing.decide(*args)
                elif action == 'revoke':
                    value = self.engine.revoke_owner(*args)
                elif action == 'permission':
                    name, enabled = args
                    if name not in self.engine.permissions:
                        raise ValueError('Неизвестное разрешение')
                    self.engine.permissions[name] = bool(enabled)
                    value = None
                elif action == 'submit':
                    value = self.engine.submit('desktop', *args)
                elif action == 'approval':
                    value = self.engine.decide(args[0], 'desktop', args[1])
                elif action == 'cancel':
                    value = self.engine.cancel(args[0], 'desktop')
                else:
                    raise ValueError('Неизвестная операция интерфейса')
                self.messages.put((action, value))
            except ValueError as error:
                self.messages.put(('error', str(error)))
            except Exception as error:
                self.messages.put(('error', type(error).__name__))
        asyncio.run_coroutine_threadsafe(dispatch(), self.loop)

    def stop(self):
        if hasattr(self, 'server') and hasattr(self, 'loop') and self.thread.is_alive():
            self.loop.call_soon_threadsafe(setattr, self.server, 'should_exit', True)
            self.thread.join(timeout=8)
