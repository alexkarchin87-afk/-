import hashlib
import json
import secrets
import sqlite3
import time
from pathlib import Path


class Store:
    """Called only on the server event loop; device tokens are stored hashed."""
    def __init__(self, path: Path):
        self.db = sqlite3.connect(path)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT, hash TEXT UNIQUE, active INTEGER);
        CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY, owner TEXT, created REAL, body TEXT);
        CREATE TABLE IF NOT EXISTS audit(at REAL, task TEXT, event TEXT);
        CREATE TABLE IF NOT EXISTS chats(id TEXT PRIMARY KEY, owner TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, created REAL NOT NULL);
        ''')
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
        self.db.commit()

    def history(self, owner='desktop'):
        rows = self.db.execute('SELECT body FROM tasks WHERE owner=? OR ?=? ORDER BY created DESC LIMIT 50',
                               (owner, owner, 'desktop'))
        return [json.loads(row[0]) for row in rows]

    def create_chat(self, owner, kind):
        from backend.chat_types import CHAT_TYPES
        if kind not in CHAT_TYPES:
            raise ValueError('Неизвестный тип чата')
        cid = secrets.token_hex(16)
        title = CHAT_TYPES[kind]['marker'] + ' ' + CHAT_TYPES[kind]['label']
        self.db.execute('INSERT INTO chats VALUES(?,?,?,?,?)', (cid, owner, kind, title, time.time()))
        self.db.commit()
        return dict(id=cid, owner=owner, kind=kind, title=title)

    def chats(self, owner='desktop'):
        rows = self.db.execute('SELECT id,owner,kind,title FROM chats WHERE owner=? ORDER BY created DESC', (owner,))
        return [dict(zip(('id', 'owner', 'kind', 'title'), row)) for row in rows]

    def owns_chat(self, cid, owner):
        return self.db.execute('SELECT 1 FROM chats WHERE id=? AND owner=?', (cid, owner)).fetchone() is not None

    def audit(self, tid, event):
        # No request bodies, paths, URLs, tokens, codes, or exception strings.
        self.db.execute('INSERT INTO audit VALUES(?,?,?)', (time.time(), tid, event))
        self.db.execute('DELETE FROM audit WHERE rowid NOT IN (SELECT rowid FROM audit ORDER BY rowid DESC LIMIT 1000)')
        self.db.commit()

    def logs(self):
        return [list(row) for row in self.db.execute('SELECT at,task,event FROM audit ORDER BY at DESC LIMIT 100')]

    def close(self):
        self.db.close()
