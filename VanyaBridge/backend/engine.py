import asyncio
import logging
import time
import uuid
from dataclasses import asdict

from backend.agents.planner import Planner

LOG = logging.getLogger('vanyabridge')
TERMINAL = {'COMPLETED', 'FAILED', 'CANCELLED'}
ACTIONS = ('list_files', 'status', 'search_images', 'read_page', 'open_url', 'launch', 'open_file', 'wallpaper')


class Engine:
    def __init__(self, store, browser, system, timeout=45, approval_timeout=120):
        self.store, self.browser, self.system = store, browser, system
        self.planner = Planner()
        self.timeout, self.approval_timeout = timeout, approval_timeout
        self.tasks, self.workers, self.approvals = {}, {}, {}
        self.permissions = {a: a in ('list_files', 'status', 'search_images', 'read_page') for a in ACTIONS}
        self.lock = asyncio.Lock()  # One executing tool at a time on a low-memory PC.

    def emit(self, task, agent, text, state=None):
        if state:
            task['state'] = state
        task['messages'].append({'agent': agent, 'text': text[:13000]})
        self.store.save(task)
        if state:
            self.store.audit(task['id'], state)
            LOG.info('task=%s state=%s', task['id'], state)

    def submit(self, owner, text, mode, request_id, chat_id=None):
        if not text.strip() or len(text) > 2000 or mode not in ('Ultra Free', 'Standard'):
            raise ValueError('Недопустимая команда или режим')
        uuid.UUID(request_id)
        if chat_id is not None and (owner != 'desktop' or not self.store.chat_exists(chat_id)):
            raise ValueError('Чат недоступен')
        tid = request_id
        existing = self.tasks.get(tid)
        if not existing:
            row = self.store.db.execute('SELECT body FROM tasks WHERE id=?', (tid,)).fetchone()
            if row:
                import json
                existing = json.loads(row[0])
        if existing:
            if existing['owner'] != owner or existing['input'] != text or existing['mode'] != mode or existing.get('chat_id') != chat_id:
                raise ValueError('Идентификатор запроса уже использован')
            return existing  # Network retry never re-executes an accepted command.
        if sum(t['state'] not in TERMINAL for t in self.tasks.values()) >= 8:
            raise ValueError('Очередь заполнена (8 задач); дождитесь завершения')
        for old_id in list(self.tasks):
            if len(self.tasks) < 100:
                break
            if self.tasks[old_id]['state'] in TERMINAL:
                del self.tasks[old_id]
        task = dict(id=tid, owner=owner, input=text, mode=mode, created=time.time(), state='IDLE',
                    messages=[], result=None, plan=None, chat_id=chat_id)
        self.tasks[tid] = task
        self.store.save(task)
        worker = asyncio.create_task(self.run(task))
        self.workers[tid] = worker
        worker.add_done_callback(lambda _: self.workers.pop(tid, None))
        return task

    def own(self, tid, owner):
        task = self.tasks.get(tid)
        if not task or owner not in (task['owner'], 'desktop'):
            raise ValueError('Задача недоступна')
        return task

    def decide(self, tid, owner, approve):
        task = self.own(tid, owner)
        if task['state'] != 'WAITING_APPROVAL':
            raise ValueError('Задача не ожидает подтверждения')
        future = self.approvals[tid]
        if future.done():
            raise ValueError('Решение уже принято')
        future.set_result(approve)

    def cancel(self, tid, owner):
        task = self.own(tid, owner)
        if task['state'] not in TERMINAL:
            self.workers[tid].cancel()
            # Mark immediately, including cancellation before the coroutine's first instruction.
            self.emit(task, 'Planner', 'Задача отменена. Уже выполненные действия не откатываются.', 'CANCELLED')

    async def run(self, task):
        tid = task['id']
        try:
            self.emit(task, 'Planner', 'Проверяю поддерживаемую команду', 'PLANNING')
            plan = self.planner.plan(task['input'], task['mode'])
            task['plan'] = asdict(plan)
            if not self.permissions[plan.action]:
                raise ValueError('Действие выключено в разрешениях на ПК: ' + plan.action)
            if plan.approval:
                future = asyncio.get_running_loop().create_future()
                self.approvals[tid] = future
                self.emit(task, plan.agent, f'Подтвердите {plan.action}: {plan.argument}', 'WAITING_APPROVAL')
                if not await asyncio.wait_for(future, self.approval_timeout):
                    self.emit(task, 'Planner', 'В разрешении отказано', 'CANCELLED')
                    return
            async with asyncio.timeout(self.timeout):
                async with self.lock:
                    if not self.permissions[plan.action]:
                        raise ValueError('Разрешение отозвано')
                    self.emit(task, plan.agent, 'Выполняю ' + plan.action, 'RUNNING')
                    argument = plan.argument
                    if plan.action == 'wallpaper' and argument.startswith('image:'):
                        argument = await self.browser.download_selection(argument[6:])
                        # A downloader may swallow cancellation; never apply a late result.
                        if asyncio.current_task().cancelling() or task['state'] == 'CANCELLED':
                            raise asyncio.CancelledError()
                        # Permission can be revoked while download is in flight.
                        if not self.permissions[plan.action]:
                            raise ValueError('Разрешение отозвано')
                    agent = self.browser if plan.agent == 'BrowserAgent' else self.system
                    result = await agent.execute(plan.action, argument)
                    # Discard late values even if a tool accidentally swallowed cancellation.
                    if asyncio.current_task().cancelling() or task['state'] == 'CANCELLED':
                        raise asyncio.CancelledError()
                    task['result'] = result
                    self.emit(task, plan.agent, result.get('text', 'Завершено'), 'COMPLETED')
        except asyncio.CancelledError:
            if task['state'] != 'CANCELLED':
                self.emit(task, 'Planner', 'Задача отменена', 'CANCELLED')
        except TimeoutError:
            if task['state'] != 'CANCELLED':
                self.emit(task, 'Planner', 'Истекло время ожидания; повторите задачу при необходимости', 'FAILED')
        except ValueError as error:
            if task['state'] != 'CANCELLED':
                self.emit(task, 'Planner', str(error), 'FAILED')
        except Exception as error:
            # Class only: upstream exceptions may contain credentials or page content.
            if task['state'] != 'CANCELLED':
                self.emit(task, 'Planner', 'Инструмент недоступен (' + type(error).__name__ +
                          '). Проверьте сеть, зависимости и разрешения. Результат не получен.', 'FAILED')
        finally:
            self.approvals.pop(tid, None)

    def revoke_owner(self, owner):
        self.store.revoke(owner)
        for tid, task in list(self.tasks.items()):
            if task['owner'] == owner and task['state'] not in TERMINAL:
                self.cancel(tid, 'desktop')

    def snapshot(self, owner='desktop'):
        visible = [t for t in self.tasks.values() if owner in (t['owner'], 'desktop')]
        agents = []
        for name in ('Planner', 'BrowserAgent', 'SystemAgent'):
            state = 'IDLE'
            for task in visible:
                if name == 'Planner' and task['state'] == 'PLANNING':
                    state = 'PLANNING'
                elif (task.get('plan') or {}).get('agent') == name and task['state'] in ('RUNNING', 'WAITING_APPROVAL'):
                    if state != 'RUNNING':
                        state = task['state']
            agents.append({'name': name, 'state': state})
        return {'tasks': self.store.history(owner), 'permissions': dict(self.permissions),
                'agents': agents, 'mode_info': 'Локальные инструменты, не три LLM'}

    async def close(self):
        for tid in list(self.workers):
            self.cancel(tid, 'desktop')
        await asyncio.gather(*list(self.workers.values()), return_exceptions=True)
