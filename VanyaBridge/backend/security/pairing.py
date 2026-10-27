import secrets
import time


class Pairing:
    def __init__(self, store, clock=time.monotonic):
        self.store, self.clock = store, clock
        self.code = ''
        self.expires = 0.0
        self.attempts = 0
        self.pending = {}

    def rotate(self):
        # Only callable from local desktop UI, never from a network endpoint.
        self.code = f'{secrets.randbelow(1_000_000):06}'
        self.expires = self.clock() + 120
        self.attempts = 0
        return self.code

    def request(self, code, name):
        self.attempts += 1
        if self.attempts > 5 or self.clock() >= self.expires or not secrets.compare_digest(code, self.code):
            raise ValueError('Код недействителен или исчерпаны попытки; создайте новый на ПК')
        self.expires = 0  # One use, even while desktop approval is pending.
        self.prune()
        rid, secret = secrets.token_hex(16), secrets.token_urlsafe(32)
        self.pending[rid] = dict(name=name, secret=secret, expires=self.clock()+120, approved=False)
        return {'id': rid, 'secret': secret}

    def prune(self):
        self.pending = {k:v for k,v in self.pending.items() if v['expires'] > self.clock()}

    def list_pending(self):
        self.prune()
        return [{'id': k, 'name': v['name']} for k,v in self.pending.items() if not v['approved']]

    def decide(self, rid, approve):
        self.prune()
        if rid not in self.pending:
            raise ValueError('Запрос истёк')
        if approve:
            self.pending[rid]['approved'] = True
        else:
            del self.pending[rid]

    def claim(self, rid, secret):
        self.prune()
        item = self.pending.get(rid)
        if not item or not secrets.compare_digest(item['secret'], secret):
            raise ValueError('Запрос истёк или не найден')
        if not item['approved']:
            return {'status': 'pending'}
        del self.pending[rid]
        did, token = self.store.device(item['name'])
        return {'status': 'paired', 'device_id': did, 'token': token}
