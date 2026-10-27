import asyncio
import uuid

import pytest

from backend.chat_types import CHAT_TYPES
from backend.engine import Engine
from backend.store import Store


class FakeTool:
    def __init__(self):
        self.calls = []

    async def execute(self, action, argument):
        self.calls.append(action)
        return {'text': 'ok'}


def test_chat_categories_persist_without_losing_old_tasks(tmp_path):
    path = tmp_path / 'db.sqlite'
    store = Store(path)
    chat = store.create_chat('desktop', 'code')
    store.save(dict(id='old', owner='desktop', created=1., state='COMPLETED',
                    messages=[], input='статус', mode='Ultra Free'))
    assert store.owns_chat(chat['id'], 'desktop')
    assert not store.owns_chat(chat['id'], 'phone')
    assert set(CHAT_TYPES) == {'code', 'photo', 'text', 'analysis'}
    with pytest.raises(ValueError):
        store.create_chat('desktop', 'wrong')
    store.close()
    reopened = Store(path)
    assert reopened.chats() == [chat]
    assert reopened.history()[0]['id'] == 'old'
    reopened.close()


def test_chat_modes_deny_cross_type_tools(tmp_path):
    async def scenario():
        store = Store(tmp_path / 'db.sqlite')
        tool = FakeTool()
        engine = Engine(store, tool, tool)
        code = store.create_chat('desktop', 'code')
        photo = store.create_chat('desktop', 'photo')
        bad = engine.submit('desktop', 'найди изображения гор', 'Ultra Free',
                            str(uuid.uuid4()), code['id'])
        good = engine.submit('desktop', 'найди изображения гор', 'Ultra Free',
                             str(uuid.uuid4()), photo['id'])
        await asyncio.gather(*engine.workers.values())
        assert bad['state'] == 'FAILED'
        assert good['state'] == 'COMPLETED'
        assert tool.calls == ['search_images']
        with pytest.raises(ValueError):
            engine.submit('phone', 'статус', 'Ultra Free', str(uuid.uuid4()), code['id'])
        with pytest.raises(ValueError):
            engine.submit('desktop', 'статус', 'Ultra Free', str(uuid.uuid4()), 'missing')
        await engine.close()
        store.close()
    asyncio.run(scenario())
