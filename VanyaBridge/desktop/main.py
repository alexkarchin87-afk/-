import argparse
import queue
import sys
import uuid
import webbrowser

from PySide6.QtCore import Qt, QTimer, QSettings
from PySide6.QtGui import QKeySequence, QShortcut
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QFrame, QHBoxLayout,
    QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow, QMessageBox,
    QPushButton, QScrollArea, QStackedWidget, QTextEdit, QVBoxLayout, QWidget)

from backend.runtime import Runtime
from backend.engine import ACTIONS

STATES = {'IDLE':'Готов', 'PLANNING':'Обрабатывает запрос', 'RUNNING':'Выполняет действие',
          'WAITING_APPROVAL':'Ожидает разрешения', 'COMPLETED':'Завершено',
          'FAILED':'Ошибка', 'CANCELLED':'Отменено'}
PERMISSIONS = {'list_files':'Просмотр тестовой папки', 'status':'Состояние компьютера',
 'search_images':'Поиск изображений', 'read_page':'Чтение разрешённых страниц',
 'open_url':'Открытие сайтов', 'launch':'Открытие VS Code', 'open_file':'Открытие файлов',
 'wallpaper':'Смена обоев'}

class Composer(QTextEdit):
    def __init__(self, submit):
        super().__init__(); self.submit = submit
        self.setPlaceholderText('Напиши команду Ване…')
        self.setFixedHeight(86)
    def keyPressEvent(self, event):
        if event.key() in (Qt.Key_Return, Qt.Key_Enter) and not (event.modifiers() & Qt.ShiftModifier):
            self.submit(); event.accept(); return
        super().keyPressEvent(event)


def clear_layout(layout):
    while layout.count():
        item = layout.takeAt(0)
        if item.widget(): item.widget().deleteLater()
        elif item.layout(): clear_layout(item.layout())


def label(text, name=None):
    w = QLabel(text); w.setTextFormat(Qt.PlainText); w.setWordWrap(True)
    if name: w.setObjectName(name)
    return w

class Desktop(QMainWindow):
    def __init__(self, runtime):
        super().__init__(); self.runtime = runtime
        self.data = None; self.active = None; self.waiting_submit = False
        self.render_key = None; self.nav_key = None; self.pair_code = 'Код ещё не создан'
        self.settings = QSettings('VanyaBridge', 'Desktop')
        self.setWindowTitle('Ваня — VanyaBridge'); self.resize(1100, 760); self.setMinimumSize(680, 500)
        root = QWidget(); self.setCentralWidget(root); h = QHBoxLayout(root); h.setContentsMargins(0,0,0,0); h.setSpacing(0)
        self.sidebar = QFrame(); self.sidebar.setObjectName('sidebar'); self.sidebar.setFixedWidth(250)
        side = QVBoxLayout(self.sidebar); side.setContentsMargins(16,20,16,16); side.setSpacing(10)
        brand = label('✦  Ваня', 'brand'); side.addWidget(brand)
        new = QPushButton('＋  Новый чат'); new.setObjectName('newChat'); new.clicked.connect(lambda:self.runtime.call('new_chat')); side.addWidget(new)
        side.addWidget(label('БЕСЕДЫ', 'caption'))
        self.chats = QListWidget(); self.chats.setObjectName('chatList'); self.chats.currentItemChanged.connect(self.select_chat); side.addWidget(self.chats, 1)
        settings_button = QPushButton('⚙  Настройки и устройства'); settings_button.clicked.connect(self.show_settings); side.addWidget(settings_button)
        h.addWidget(self.sidebar)
        self.pages = QStackedWidget(); h.addWidget(self.pages, 1)
        chat = QWidget(); main = QVBoxLayout(chat); main.setContentsMargins(26,18,26,18); main.setSpacing(14)
        top = QHBoxLayout(); toggle = QPushButton('☰'); toggle.setObjectName('iconButton'); toggle.setToolTip('Свернуть или открыть панель'); toggle.clicked.connect(lambda:self.sidebar.setVisible(not self.sidebar.isVisible())); top.addWidget(toggle)
        titlecol = QVBoxLayout(); self.title = label('Новый чат','title'); self.status = label('Готов','subtle'); titlecol.addWidget(self.title); titlecol.addWidget(self.status); top.addLayout(titlecol,1); main.addLayout(top)
        self.scroll = QScrollArea(); self.scroll.setWidgetResizable(True); self.scroll.setFrameShape(QFrame.NoFrame)
        self.feed = QWidget(); self.feed_layout = QVBoxLayout(self.feed); self.feed_layout.setContentsMargins(4,8,4,8); self.feed_layout.setSpacing(16); self.scroll.setWidget(self.feed); main.addWidget(self.scroll,1)
        self.composer = Composer(self.submit); self.composer.setObjectName('composer'); main.addWidget(self.composer)
        bottom=QHBoxLayout(); bottom.addWidget(label('Enter — отправить  ·  Shift+Enter — новая строка','subtle'),1)
        manual=QPushButton('ChatGPT ↗'); manual.setToolTip('Скопировать запрос и открыть ChatGPT вручную'); manual.clicked.connect(self.manual_chatgpt); bottom.addWidget(manual)
        send = QPushButton('Отправить  ↑'); send.setObjectName('primary'); send.clicked.connect(self.submit); bottom.addWidget(send); main.addLayout(bottom)
        self.pages.addWidget(chat)
        self.settings_page = QWidget(); self.settings_layout = QVBoxLayout(self.settings_page); self.settings_layout.setContentsMargins(30,20,30,20); self.pages.addWidget(self.settings_page)
        self.apply_theme(self.settings.value('theme','dark'))
        self.timer=QTimer(self); self.timer.timeout.connect(self.poll); self.timer.start(350)
        self.runtime.call('snapshot')

    def apply_theme(self, theme):
        self.theme = theme; self.settings.setValue('theme', theme)
        dark = theme == 'dark'
        bg, side, panel, text, muted, border, accent = (('#17191d','#202329','#2d3138','#f2f3f5','#a9afb9','#3b4048','#8eacff') if dark else ('#f9f9f8','#efefed','#ffffff','#202124','#666d75','#d8dbe0','#315fce'))
        self.setStyleSheet(f'''QMainWindow,QWidget {{background:{bg};color:{text};font-family:"Segoe UI";font-size:14px;}}
        QFrame#sidebar {{background:{side};border-right:1px solid {border};}} QFrame#sidebar QWidget {{background:{side};}}
        QLabel#brand {{font-size:23px;font-weight:700;}} QLabel#title {{font-size:19px;font-weight:600;}}
        QLabel#subtle,QLabel#caption {{color:{muted};font-size:12px;}} QLabel#caption {{font-weight:600;}}
        QPushButton {{background:{panel};border:1px solid {border};border-radius:9px;padding:9px 12px;text-align:left;}}
        QPushButton:hover {{border-color:{accent};}} QPushButton#primary {{background:{accent};color:{'#10131a' if dark else '#ffffff'};font-weight:600;}}
        QPushButton#iconButton {{border:0;padding:7px;font-size:18px;}} QListWidget {{border:0;outline:0;}}
        QListWidget::item {{padding:10px;border-radius:8px;}} QListWidget::item:selected {{background:{panel};color:{text};}}
        QTextEdit,QLineEdit,QComboBox {{background:{panel};border:1px solid {border};border-radius:10px;padding:9px;selection-background-color:{accent};}}
        QScrollArea {{border:0;}} QFrame#userBubble,QFrame#answerBubble,QFrame#approval {{background:{panel};border:1px solid {border};border-radius:12px;}}
        QFrame#userBubble QLabel,QFrame#answerBubble QLabel,QFrame#approval QLabel {{background:transparent;}}
        QCheckBox {{spacing:10px;padding:5px;}}''')

    def select_chat(self, current, previous):
        if current:
            self.active=current.data(Qt.UserRole); self.pages.setCurrentIndex(0); self.render_key=None; self.render()

    def show_settings(self):
        self.pages.setCurrentIndex(1); self.render_settings()

    def manual_chatgpt(self):
        QApplication.clipboard().setText(self.composer.toPlainText())
        webbrowser.open('https://chatgpt.com/')

    def submit(self, text=None):
        if isinstance(text, bool): text=None
        text = self.composer.toPlainText().strip() if text is None else text
        if not text or not self.active or self.waiting_submit: return
        self.waiting_submit=True
        self.runtime.call('chat_submit', self.active, text, 'Ultra Free', str(uuid.uuid4()))

    def poll(self):
        try:
            while True:
                action,value=self.runtime.messages.get_nowait()
                if action == 'snapshot':
                    self.data=value; self.update_nav(); self.render()
                    if self.pages.currentIndex()==1: self.render_settings()
                elif action == 'new_chat': self.active=value['id']; self.nav_key=None
                elif action == 'chat_submit':
                    self.waiting_submit=False
                    if self.composer.toPlainText().strip() == value['input']:
                        self.composer.clear()
                elif action == 'pair_code': self.pair_code='Одноразовый код: '+value; self.render_settings()
                elif action == 'error':
                    self.waiting_submit=False; QMessageBox.warning(self,'Ваня',value)
        except queue.Empty: pass
        if self.runtime.thread.is_alive(): self.runtime.call('snapshot')
        else: self.status.setText('Сервер остановлен')

    def update_nav(self):
        if not self.data: return
        chats=self.data['chats']; key=[(c['id'],c['title']) for c in chats]
        if not self.active and chats: self.active=chats[0]['id']
        if key == self.nav_key and self.chats.currentItem() and self.chats.currentItem().data(Qt.UserRole)==self.active: return
        self.nav_key=key; self.chats.blockSignals(True); self.chats.clear()
        for c in chats:
            item=QListWidgetItem(c['title']); item.setData(Qt.UserRole,c['id']); self.chats.addItem(item)
            if c['id']==self.active: self.chats.setCurrentItem(item)
        self.chats.blockSignals(False)

    def card(self, text, kind, align):
        row=QHBoxLayout(); card=QFrame(); card.setObjectName(kind); content=QVBoxLayout(card); content.setContentsMargins(14,11,14,11)
        content.addWidget(label(text)); card.setMinimumWidth(220); card.setMaximumWidth(720)
        if align=='right': row.addStretch(1)
        row.addWidget(card)
        if align=='left': row.addStretch(1)
        self.feed_layout.addLayout(row); return content

    def render(self):
        if not self.data or not self.active: return
        chat=next((c for c in self.data['chats'] if c['id']==self.active),None)
        if not chat: return
        key=(self.active,repr(chat['tasks']))
        self.title.setText(chat['title'])
        tasks=chat['tasks']
        current=next((t for state in ('WAITING_APPROVAL','RUNNING','PLANNING','IDLE') for t in reversed(tasks) if t['state']==state), tasks[-1] if tasks else None)
        self.status.setText(STATES.get(current['state'],'Готов') if current else 'Готов')
        if self.render_key==key: return
        self.render_key=key; clear_layout(self.feed_layout)
        if not tasks:
            self.feed_layout.addStretch(1); self.feed_layout.addWidget(label('Чем я могу помочь?', 'title'),0,Qt.AlignHCenter)
            self.feed_layout.addWidget(label('Попробуй: «статус компьютера» или «покажи файлы».\nСейчас доступны команды, свободный диалог пока не подключён.', 'subtle'),0,Qt.AlignHCenter); self.feed_layout.addStretch(1)
        for task in tasks:
            self.card(task['input'],'userBubble','right')
            messages=task.get('messages',[]); state=task['state']; result=task.get('result') or {}
            txt=messages[-1]['text'] if messages else STATES.get(state,'Готов')
            if state=='WAITING_APPROVAL':
                plan=task.get('plan') or {}; action=plan.get('action',''); arg=plan.get('argument','')
                txt='Ваня запрашивает действие: '+PERMISSIONS.get(action,action)+'\n'+arg
                box=self.card(txt,'approval','left'); buttons=QHBoxLayout()
                for title, decision in [('Разрешить',True),('Отклонить',False)]:
                    b=QPushButton(title); b.clicked.connect(lambda checked=False,tid=task['id'],d=decision:self.runtime.call('approval',tid,d)); buttons.addWidget(b)
                box.addLayout(buttons)
            else:
                box=self.card(txt,'answerBubble','left')
                if 'free_disk_bytes' in result:
                    box.addWidget(label('Свободно на диске: {:.1f} ГБ'.format(result['free_disk_bytes']/1_000_000_000)))
                if result.get('files'): box.addWidget(label('\n'.join(f["name"] for f in result['files'])))
                for item in result.get('images',[]):
                    b=QPushButton('Посмотреть: '+item['title']); b.clicked.connect(lambda checked=False,u=item['url']:webbrowser.open(u)); box.addWidget(b)
                    b2=QPushButton('Выбрать для обоев'); b2.clicked.connect(lambda checked=False,i=item['id']:self.submit('установи обои image:'+i)); box.addWidget(b2)
                actions=QHBoxLayout(); copy=QPushButton('Копировать'); copy.clicked.connect(lambda checked=False,t=txt:QApplication.clipboard().setText(t)); actions.addWidget(copy)
                if state=='FAILED':
                    retry=QPushButton('Повторить'); retry.clicked.connect(lambda checked=False,t=task['input']:self.submit(t)); actions.addWidget(retry)
                if state in ('IDLE','PLANNING','RUNNING'):
                    cancel=QPushButton('Отменить'); cancel.clicked.connect(lambda checked=False,tid=task['id']:self.runtime.call('cancel',tid)); actions.addWidget(cancel)
                actions.addStretch(1); box.addLayout(actions)
        self.feed_layout.addStretch(1); QTimer.singleShot(0,lambda:self.scroll.verticalScrollBar().setValue(self.scroll.verticalScrollBar().maximum()))

    def render_settings(self):
        if not self.data or self.pages.currentIndex()!=1: return
        # Rebuild only on actual changes, preserving scroll and user interactions.
        key=repr((self.data['permissions'],self.data['pending'],self.data['devices'],self.data['logs'],self.pair_code,self.theme))
        if getattr(self,'settings_key',None)==key: return
        self.settings_key=key; clear_layout(self.settings_layout)
        back=QPushButton('←  Вернуться в чат'); back.clicked.connect(lambda:self.pages.setCurrentIndex(0)); self.settings_layout.addWidget(back)
        sc=QScrollArea(); sc.setWidgetResizable(True); holder=QWidget(); body=QVBoxLayout(holder); body.setSpacing(12)
        body.addWidget(label('Настройки', 'title'))
        theme=QPushButton('Тема: '+('тёмная' if self.theme=='dark' else 'светлая')+'  ·  переключить'); theme.clicked.connect(self.toggle_theme); body.addWidget(theme)
        body.addWidget(label('Разрешения', 'title')); body.addWidget(label('Действия требуют отдельного подтверждения. После перезапуска разрешения сбрасываются.','subtle'))
        for name in ACTIONS:
            check=QCheckBox(PERMISSIONS[name]); check.setChecked(self.data['permissions'][name]); check.toggled.connect(lambda v,n=name:self.runtime.call('permission',n,v)); body.addWidget(check)
        body.addWidget(label('Подключение Android','title'))
        connection=label('Адрес: https://'+self.runtime.host+':'+str(self.runtime.port)+'\nОтпечаток SHA-256: '+self.runtime.fingerprint+'\nДля телефона нужен запуск с частным LAN-адресом.')
        connection.setTextInteractionFlags(Qt.TextSelectableByMouse | Qt.TextSelectableByKeyboard)
        body.addWidget(connection)
        body.addWidget(label(self.pair_code)); code=QPushButton('Создать одноразовый код'); code.clicked.connect(lambda:self.runtime.call('pair_code')); body.addWidget(code)
        for pending in self.data['pending']:
            body.addWidget(label('Запрос: '+pending['name']))
            row=QHBoxLayout()
            for title,decision in [('Подключить',True),('Отклонить',False)]:
                b=QPushButton(title); b.clicked.connect(lambda checked=False,i=pending['id'],d=decision:self.runtime.call('pair_decide',i,d)); row.addWidget(b)
            body.addLayout(row)
        for device in self.data['devices']:
            body.addWidget(label(device['name']+(' · активно' if device['active'] else ' · отозвано')))
            if device['active']:
                b=QPushButton('Отозвать доступ'); b.clicked.connect(lambda checked=False,i=device['id']:self.runtime.call('revoke',i)); body.addWidget(b)
        body.addWidget(label('Диагностика','title')); body.addWidget(label('Папка: '+str(self.runtime.root)+'\nLLM не подключён. Режим работает по ограниченным командам.\nЖурнал событий:\n'+'\n'.join(str(row) for row in self.data['logs'][-20:]),'subtle'))
        body.addStretch(1); sc.setWidget(holder); self.settings_layout.addWidget(sc)

    def toggle_theme(self):
        self.apply_theme('light' if self.theme=='dark' else 'dark'); self.settings_key=None; self.render_settings()

    def closeEvent(self,event):
        self.timer.stop(); self.runtime.stop(); event.accept()


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--host',default='127.0.0.1'); parser.add_argument('--port',type=int,default=8765); parser.add_argument('--data',default=None); parser.add_argument('--smoke-test',action='store_true'); args=parser.parse_args()
    if args.smoke_test:
        import os
        import traceback
        try:
            from desktop.smoke import run_smoke
            run_smoke()
        except BaseException:
            if os.environ.get('VANYA_SMOKE_REPORT'):
                with open(os.environ['VANYA_SMOKE_REPORT'], 'w', encoding='utf-8') as output:
                    output.write(traceback.format_exc())
            raise SystemExit(1)
        return
    app=QApplication(sys.argv); runtime=None
    try:
        runtime=Runtime(args.host,args.port,args.data); runtime.start(); window=Desktop(runtime); window.show(); app.exec()
    except (ValueError,OSError,RuntimeError) as error:
        QMessageBox.critical(None,'Ваня: ошибка запуска',str(error))
    finally:
        if runtime: runtime.stop()

if __name__=='__main__': main()
