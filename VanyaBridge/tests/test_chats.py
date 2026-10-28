import asyncio
import uuid
import pytest
from backend.engine import Engine
from backend.store import Store

class Tool:
    async def execute(self, action, argument):
        return {'text': 'ok'}


def test_existing_tasks_migrate_and_new_chats_survive_restart(tmp_path):
    path=tmp_path/'history.db'; store=Store(path)
    store.save({'id':'old','owner':'desktop','created':1,'state':'COMPLETED','messages':[]})
    one=store.new_chat(); two=store.new_chat()
    async def run():
        engine=Engine(store,Tool(),Tool()); rid=str(uuid.uuid4())
        first=engine.submit('desktop','статус','Ultra Free',rid,one['id'])
        engine.submit('desktop','покажи файлы','Ultra Free',str(uuid.uuid4()),two['id'])
        with pytest.raises(ValueError): engine.submit('desktop','статус','Ultra Free',rid,two['id'])
        with pytest.raises(ValueError): engine.submit('phone','статус','Ultra Free',str(uuid.uuid4()),one['id'])
        await asyncio.gather(*list(engine.workers.values()))
        assert first['state']=='COMPLETED'
        store.title_chat(one['id'],'статус')
    asyncio.run(run()); store.close()
    store=Store(path)
    chats={c['id']:c for c in store.chat_snapshot()}
    assert [t['id'] for t in chats['legacy']['tasks']]==['old']
    assert chats[one['id']]['title']=='статус'
    assert [t['input'] for t in chats[one['id']]['tasks']]==['статус']
    assert [t['input'] for t in chats[two['id']]['tasks']]==['покажи файлы']
    store.close()


def test_chat_cache_updates_after_new_task_and_title(tmp_path):
    store=Store(tmp_path/'history.db')
    try:
        first=store.chat_snapshot()
        assert first is store.chat_snapshot()
        chat=store.new_chat(); second=store.chat_snapshot()
        assert second is not first and any(c['id']==chat['id'] for c in second)
        store.save({'id':'new','owner':'desktop','created':1,'state':'COMPLETED','messages':[], 'chat_id':chat['id']})
        assert len(next(c for c in store.chat_snapshot() if c['id']==chat['id'])['tasks'])==1
        store.title_chat(chat['id'],'Название')
        assert next(c for c in store.chat_snapshot() if c['id']==chat['id'])['title']=='Название'
    finally: store.close()
