"""Exercise the actual Qt GUI and runtime with temporary data, no paid services."""
import os
import socket
import tempfile
import time

from PySide6.QtWidgets import QApplication, QPushButton
from backend.runtime import Runtime
from desktop.main import Desktop


def run_smoke():
    app=QApplication.instance() or QApplication([])
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix='vanya-smoke-') as data:
        runtime=Runtime(port=port,data=data); runtime.start(); window=Desktop(runtime); window.show()
        def wait(predicate, name):
            deadline=time.monotonic()+15
            while time.monotonic()<deadline:
                app.processEvents()
                if predicate(): return
                time.sleep(.02)
            raise RuntimeError('Timed out: '+name)
        try:
            wait(lambda:window.data is not None and window.active is not None,'snapshot')
            new=window.findChild(QPushButton,'newChat'); new.click()
            wait(lambda:window.active!='legacy' and any(c['id']==window.active for c in window.data['chats']),'new chat')
            first=window.active
            window.composer.setPlainText('статус'); window.submit()
            wait(lambda:any(c['id']==first and c['tasks'] and c['tasks'][0]['state']=='COMPLETED' for c in window.data['chats']),'status')
            window.runtime.call('permission','launch',True)
            wait(lambda:window.data['permissions']['launch'],'permission')
            window.composer.setPlainText('открой VS Code'); window.submit()
            wait(lambda:any(c['id']==first and len(c['tasks'])>=2 and c['tasks'][-1]['state']=='WAITING_APPROVAL' for c in window.data['chats']),'approval card')
            wait(lambda:any(b.text()=='Отклонить' for b in window.feed.findChildren(QPushButton)),'decline button')
            report=os.environ.get('VANYA_SCREENSHOT')
            if report: window.grab().save(report)
            next(b for b in window.feed.findChildren(QPushButton) if b.text()=='Отклонить').click()
            wait(lambda:any(c['id']==first and c['tasks'][-1]['state']=='CANCELLED' for c in window.data['chats']),'decline')
            new.click(); wait(lambda:window.active!=first and window.active!='legacy' and any(c['id']==window.active for c in window.data['chats']) and window.chats.count()>=3,'second chat')
            second=window.active
            for i in range(window.chats.count()):
                if window.chats.item(i).data(256)==first: window.chats.setCurrentRow(i); break
            wait(lambda:window.active==first,'switch back')
            window.resize(700,520); app.processEvents()
            assert window.width()==700 and window.isVisible()
        finally:
            window.close(); app.processEvents()
        if runtime.thread.is_alive(): raise RuntimeError('Server did not stop')
        restarted=Runtime(port=port,data=data); restarted.start()
        try:
            restarted.call('snapshot'); action,value=restarted.messages.get(timeout=10)
            assert action=='snapshot'
            chats={c['id']:c for c in value['chats']}
            assert second in chats and first in chats and len(chats[first]['tasks'])==2
        finally: restarted.stop()
