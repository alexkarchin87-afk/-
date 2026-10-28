"""Exercise Qt widgets with the real Runtime and a local TLS pairing client."""
import os
import socket
import time

if os.name != 'nt' and not os.environ.get('DISPLAY'):
    os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

import httpx
from PySide6.QtCore import Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QCheckBox, QMessageBox, QPushButton
from backend.runtime import Runtime
from desktop.main import Desktop, PERMISSIONS


def wait(app, check, reason):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        app.processEvents()
        if check(): return
        time.sleep(.02)
    raise AssertionError('Timeout: ' + reason)


def button(window, text):
    return next((b for b in window.findChildren(QPushButton) if b.isVisible() and b.text() == text), None)


def test_ui_keyboard_settings_pairing_and_restart(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    runtime = Runtime(port=port, data=tmp_path); runtime.start()
    window = Desktop(runtime); window.show()
    try:
        wait(app, lambda: window.data and window.active, 'initial snapshot')
        initial = window.theme
        window.show_settings()
        wait(app, lambda: button(window, 'Создать одноразовый код'), 'settings')
        before = window.theme
        theme_button = next(b for b in window.findChildren(QPushButton) if b.isVisible() and b.text().startswith('Тема:'))
        theme_button.click(); assert window.theme != before
        checkbox = next(c for c in window.findChildren(QCheckBox) if c.isVisible() and c.text() == PERMISSIONS['launch'])
        checkbox.click()
        wait(app, lambda: window.data['permissions']['launch'], 'permission switch')
        wait(app, lambda: button(window, 'Создать одноразовый код'), 'code button')
        button(window, 'Создать одноразовый код').click()
        wait(app, lambda: window.pair_code.startswith('Одноразовый код: '), 'pair code')
        code = window.pair_code.split(': ')[1]
        with httpx.Client(base_url=f'https://127.0.0.1:{port}', verify=False, trust_env=False, timeout=5) as client:
            request = client.post('/pair/request', json={'code':code, 'name':'test phone'}).json()
            assert client.post('/pair/claim',json=request).json()['status']=='pending'
            wait(app, lambda: any(p['name']=='test phone' for p in window.data['pending']) and button(window,'Подключить'), 'pending device')
            button(window,'Подключить').click()
            wait(app, lambda: not window.data['pending'], 'desktop pairing approval')
            result=client.post('/pair/claim',json=request).json()
            assert result['status']=='paired'
            headers={'Authorization':'Bearer '+result['token']}
            assert client.get('/snapshot',headers=headers).status_code==200
            wait(app, lambda: button(window,'Отозвать доступ'), 'device entry')
            button(window,'Отозвать доступ').click()
            wait(app, lambda: any(not d['active'] for d in window.data['devices']), 'revocation')
            assert client.get('/snapshot',headers=headers).status_code==401
        window.toggle_theme()
        assert window.theme == initial
        button(window, '←  Вернуться в чат').click()
        window.composer.setFocus(); QTest.keyClicks(window.composer, 'abc')
        QTest.keyClick(window.composer, Qt.Key_Return, Qt.ShiftModifier)
        QTest.keyClicks(window.composer, 'def')
        assert window.composer.toPlainText() == 'abc\ndef'
        window.composer.setPlainText('статус')
        QTest.keyClick(window.composer, Qt.Key_Return)
        wait(app, lambda: any(t['input']=='статус' and t['state']=='COMPLETED' for c in window.data['chats'] for t in c['tasks']), 'keyboard submit')
        assert window.composer.toPlainText() == ''
        assert window.status.text() == 'Завершено'
        wait(app, lambda: any(b.isVisible() and b.text()=='Копировать' for b in window.feed.findChildren(QPushButton)), 'copy button visible')
        next(b for b in window.feed.findChildren(QPushButton) if b.isVisible() and b.text()=='Копировать').click()
        assert app.clipboard().text()
        errors=[]
        monkeypatch.setattr(QMessageBox, 'warning', lambda *args: errors.append(args[-1]))
        window.composer.setPlainText('x' * 2001)
        QTest.keyClick(window.composer, Qt.Key_Return)
        wait(app, lambda: bool(errors), 'submit error')
        assert window.composer.toPlainText() == 'x' * 2001
    finally:
        window.apply_theme(initial if 'initial' in locals() else 'dark')
        window.close(); app.processEvents()
