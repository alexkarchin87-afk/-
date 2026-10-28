import hashlib
import json
import secrets
import sqlite3
import time
import uuid
from pathlib import Path


class Store:
    """Called only on the server event loop; device tokens are stored hashed."""
    def __init__(self, path: Path):
        self.db = sqlite3.connect(path)
        self._chat_cache = None
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT, hash TEXT UNIQUE, active INTEGER);
        CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY, owner TEXT, created REAL, body TEXT);
        CREATE TABLE IF NOT EXISTS audit(at REAL, task TEXT, event TEXT);
        ''')
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS chats(id TEXT PRIMARY KEY, title TEXT NOT NULL, created REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS chat_tasks(task_id TEXT PRIMARY KEY, chat_id TEXT NOT NULL);
        ''')
        self.db.execute("INSERT OR IGNORE INTO chats VALUES ('legacy', 'Предыдущие задачи', 0)")
        self.db.execute("INSERT OR IGNORE INTO chat_tasks SELECT id, 'legacy' FROM tasks")
        self.db.commit()
        # Interrupted tasks never resume privileged actions after restart.
        for tid, body in self.db.execute('SELECT id, body FROM tasks').fetchall():
            task = json.loads(body)
            if task['state'] not in ('COMPLETED', 'FAILED', 'CANCELLED'):
                task['state'] = 'CANCELLED'
                task['messages'].append({'agent': 'Planner', 'text': 'Сервер перезапущен; задача отменена'})
                self.db.execute('UPDATE tasks SET body=? WHERE id=?', (json.dumps(task, ensure_ascii=False), tid))
        self.db.commit()

    def device(self, name: str):
        token, did = secrets.token_urlsafe(32), secrets.token_hex(12)
        self.db.execute('INSERT INTO devices VALUES(?,?,?,1)',
                        (did, name, hashlib.sha256(token.encode()).hexdigest()))
        self.db.commit()
        return did, token

    def authenticate(self, token: str):
        row = self.db.execute('SELECT id FROM devices WHERE hash=? AND active=1',
                              (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        return row[0] if row else None

    def devices(self):
        return [dict(zip(('id', 'name', 'active'), r)) for r in
                self.db.execute('SELECT id,name,active FROM devices')]

    def revoke(self, did):
        self.db.execute('UPDATE devices SET active=0 WHERE id=?', (did,))
        self.db.commit()

    def save(self, task):
        self.db.execute('INSERT OR REPLACE INTO tasks VALUES(?,?,?,?)',
                        (task['id'], task['owner'], task['created'], json.dumps(task, ensure_ascii=False)))
        self.db.execute('INSERT OR IGNORE INTO chat_tasks VALUES (?,?)',
                        (task['id'], task.get('chat_id') or 'legacy'))
        self.db.commit()
        self._chat_cache = None

    def new_chat(self):
        chat = {'id': uuid.uuid4().hex, 'title': 'Новый чат', 'created': time.time()}
        self.db.execute('INSERT INTO chats VALUES (?,?,?)', (chat['id'], chat['title'], chat['created']))
        self.db.commit()
        self._chat_cache = None
        return chat

    def chat_exists(self, chat_id):
        return self.db.execute('SELECT 1 FROM chats WHERE id=?', (chat_id,)).fetchone() is not None

    def chat_snapshot(self):
        if self._chat_cache is not None:
            return self._chat_cache
        chats = [dict(zip(('id', 'title', 'created'), row)) for row in self.db.execute(
            'SELECT id,title,created FROM chats ORDER BY created DESC')]
        for chat in chats:
            rows = self.db.execute('SELECT tasks.body FROM tasks JOIN chat_tasks ON tasks.id=chat_tasks.task_id WHERE chat_tasks.chat_id=? ORDER BY tasks.created', (chat['id'],))
            chat['tasks'] = [json.loads(row[0]) for row in rows]
        self._chat_cache = chats
        return chats

    def llm_context(self, chat_id, before_task):
        if chat_id is None:
            return []
        rows = self.db.execute('SELECT tasks.body FROM tasks JOIN chat_tasks ON tasks.id=chat_tasks.task_id WHERE chat_tasks.chat_id=? ORDER BY tasks.created DESC LIMIT 30', (chat_id,))
        pairs = []
        for row in rows:
            task = json.loads(row[0]); result = task.get('result') or {}
            if task['id'] != before_task and task['owner'] == 'desktop' and task['state'] == 'COMPLETED' and result.get('source') == 'local_llm':
                pairs.append((task['input'][:2000], result.get('text', '')[:2000]))
                if len(pairs) >= 4: break
        return [item for user, answer in reversed(pairs) for item in (
            {'role': 'user', 'content': user}, {'role': 'assistant', 'content': answer})]

    def title_chat(self, chat_id, text):
        self.db.execute("UPDATE chats SET title=? WHERE id=? AND title='Новый чат'", (text.strip().replace('\n', ' ')[:45], chat_id))
        self.db.commit()
        self._chat_cache = None

    def history(self, owner='desktop'):
        rows = self.db.execute('SELECT body FROM tasks WHERE owner=? OR ?=? ORDER BY created DESC LIMIT 50',
                               (owner, owner, 'desktop'))
        return [json.loads(row[0]) for row in rows]

    def audit(self, tid, event):
        # No request bodies, paths, URLs, tokens, codes, or exception strings.
        self.db.execute('INSERT INTO audit VALUES(?,?,?)', (time.time(), tid, event))
        self.db.execute('DELETE FROM audit WHERE rowid NOT IN (SELECT rowid FROM audit ORDER BY rowid DESC LIMIT 1000)')
        self.db.commit()

    def logs(self):
        return [list(row) for row in self.db.execute('SELECT at,task,event FROM audit ORDER BY at DESC LIMIT 100')]

    def close(self):
        self.db.close()
