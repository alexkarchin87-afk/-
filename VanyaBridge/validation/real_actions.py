"""Run real desktop actions only in an isolated Windows runner; report, do not fake results."""
import asyncio
import json
import os
import tempfile
import uuid
from pathlib import Path

from backend.engine import Engine
from backend.store import Store
from backend.agents.browser import BrowserAgent
from backend.agents.system import SystemAgent

async def run(root):
    store=Store(root/'test.db'); folder=root/'TestFolder'; folder.mkdir()
    (folder/'sample.txt').write_text('sample',encoding='utf-8')
    system=SystemAgent(folder); engine=Engine(store,BrowserAgent(folder),system)
    results={}
    try:
        for name,command,permission,approve in (
            ('status','статус',None,False),
            ('files','покажи файлы',None,False),
            ('vscode','открой VS Code','launch',True),
            ('youtube','открой youtube','open_url',True)):
            if permission: engine.permissions[permission]=True
            task=engine.submit('desktop',command,'Ultra Free',str(uuid.uuid4()))
            if approve:
                for _ in range(100):
                    if task['state']=='WAITING_APPROVAL': break
                    await asyncio.sleep(.05)
                if task['state']=='WAITING_APPROVAL': engine.decide(task['id'],'desktop',True)
            await asyncio.gather(*list(engine.workers.values()),return_exceptions=True)
            results[name]={'state':task['state'],'result':task['result'],
                           'message':task['messages'][-1]['text'] if task['messages'] else ''}
        return results
    finally:
        await engine.close(); store.close()

if __name__=='__main__':
    if os.name != 'nt': raise SystemExit('Windows runner required')
    with tempfile.TemporaryDirectory(prefix='vanya-real-actions-') as directory:
        print(json.dumps(asyncio.run(run(Path(directory))),ensure_ascii=True,indent=2))
