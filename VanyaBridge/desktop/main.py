import argparse
import json
import queue
import tkinter as tk
from tkinter import messagebox, ttk
import uuid
import webbrowser

from backend.runtime import Runtime
from backend.chat_types import CHAT_TYPES


class Desktop:
    def __init__(self, window, runtime):
        self.window, self.runtime = window, runtime
        self.tasks, self.devices, self.pending, self.images = [], [], [], []
        self.all_tasks, self.chats, self.active_chat = [], [], None
        self.last_snapshot = None
        self.submitting = False
        window.title('VanyaBridge · локальные агенты')
        window.geometry('1000x760')
        window.minsize(760, 560)
        style = ttk.Style(window)
        style.theme_use('clam')
        style.configure('.', font=('Segoe UI', 10))
        style.configure('TButton', padding=7)
        notebook = ttk.Notebook(window)
        notebook.pack(fill='both', expand=True, padx=12, pady=12)
        chat, connections, settings, logs = [ttk.Frame(notebook, padding=12) for _ in range(4)]
        for frame, title in zip((chat, connections, settings, logs), ('Чат и история', 'Устройства', 'Разрешения', 'Журнал')):
            notebook.add(frame, text=title)

        self.status = tk.StringVar(value=f'Сервер TLS: {runtime.host}:{runtime.port} · без LLM API')
        ttk.Label(chat, textvariable=self.status).pack(anchor='w')
        self.mode = tk.StringVar(value='Ultra Free')
        ttk.Combobox(chat, textvariable=self.mode, values=('Ultra Free', 'Standard'), state='readonly', width=15).pack(anchor='w', pady=6)
        self.agent_status = tk.StringVar(value='Planner · BrowserAgent · SystemAgent — локальные инструменты')
        ttk.Label(chat, textvariable=self.agent_status).pack(anchor='w')
        navigation = ttk.Frame(chat)
        navigation.pack(fill='x', pady=5)
        ttk.Button(navigation, text='+ Новый чат', command=self.new_chat).pack(side='left')
        ttk.Label(navigation, text='История чатов').pack(side='left', padx=12)
        self.chatlist = tk.Listbox(chat, height=4, exportselection=False)
        self.chatlist.pack(fill='x')
        self.chatlist.bind('<<ListboxSelect>>', lambda _: self.select_chat())
        self.tasklist = tk.Listbox(chat, height=7, exportselection=False)
        self.tasklist.pack(fill='x', pady=8)
        self.tasklist.bind('<<ListboxSelect>>', lambda _: self.render_task())
        self.output = tk.Text(chat, wrap='word', height=12, state='disabled')
        self.output.pack(fill='both', expand=True)
        controls = ttk.Frame(chat)
        controls.pack(fill='x', pady=6)
        for text, action in (('Подтвердить', lambda:self.decide(True)), ('Отклонить', lambda:self.decide(False)),
                             ('Отменить задачу', self.cancel)):
            ttk.Button(controls, text=text, command=action).pack(side='left')
        self.image_choice = ttk.Combobox(chat, state='readonly')
        self.image_choice.pack(fill='x')
        image_controls = ttk.Frame(chat)
        image_controls.pack(fill='x')
        ttk.Button(image_controls, text='Посмотреть выбранное', command=self.preview_image).pack(side='left')
        ttk.Button(image_controls, text='Выбранное → обои (с подтверждением)', command=self.wallpaper).pack(side='left')
        entry_row = ttk.Frame(chat)
        entry_row.pack(fill='x', pady=8)
        self.input = ttk.Entry(entry_row)
        self.input.pack(side='left', fill='x', expand=True)
        self.input.bind('<Return>', lambda _: self.submit())
        ttk.Button(entry_row, text='Отправить', command=self.submit).pack(side='right')
        ttk.Button(chat, text='Скопировать запрос и открыть официальный ChatGPT вручную', command=self.manual_chatgpt).pack(anchor='w')

        ttk.Label(connections, text='Введите на Android этот адрес и SHA-256. Затем сравните отпечаток перед сопряжением.').pack(anchor='w')
        address = ttk.Entry(connections)
        address.insert(0, f'https://{runtime.host}:{runtime.port}')
        address.configure(state='readonly')
        address.pack(fill='x', pady=6)
        fingerprint = ttk.Entry(connections)
        fingerprint.insert(0, runtime.fingerprint)
        fingerprint.configure(state='readonly')
        fingerprint.pack(fill='x')
        self.code = tk.StringVar(value='Код не создан')
        ttk.Label(connections, textvariable=self.code).pack(anchor='w', pady=8)
        ttk.Button(connections, text='Создать одноразовый код (120 секунд)', command=lambda:runtime.call('pair_code')).pack(anchor='w')
        ttk.Label(connections, text='Ожидают подтверждения (сверьте устройство):').pack(anchor='w', pady=8)
        self.pairlist = tk.Listbox(connections, height=4, exportselection=False)
        self.pairlist.pack(fill='x')
        pairrow = ttk.Frame(connections)
        pairrow.pack(fill='x')
        ttk.Button(pairrow, text='Разрешить сопряжение', command=lambda:self.pair_decide(True)).pack(side='left')
        ttk.Button(pairrow, text='Отклонить', command=lambda:self.pair_decide(False)).pack(side='left')
        self.devicelist = tk.Listbox(connections, height=6, exportselection=False)
        self.devicelist.pack(fill='x', pady=8)
        ttk.Button(connections, text='Отозвать доступ выбранного устройства', command=self.revoke).pack(anchor='w')
        ttk.Label(connections, text='По умолчанию сервер слушает только 127.0.0.1. Для телефона запустите с --host LAN_IP.').pack(anchor='w', pady=10)

        ttk.Label(settings, text=f'Разрешённая папка: {runtime.root}', wraplength=700).pack(anchor='w')
        ttk.Label(settings, text='Разрешения действуют на все сопряжённые устройства. Изменения — только на ПК.\n'
                  'После перезапуска разрешения действий сбрасываются. Каждое действие требует согласия.').pack(anchor='w', pady=8)
        self.permissions = {}
        from backend.engine import ACTIONS
        for action in ACTIONS:
            value = tk.BooleanVar()
            self.permissions[action] = value
            ttk.Checkbutton(settings, text=action, variable=value,
                            command=lambda a=action,v=value:runtime.call('permission', a, v.get())).pack(anchor='w', pady=3)
        ttk.Label(settings, text='Белый список приложений: VS Code в стандартных каталогах установки.\n'
                  'Обои: JPG/PNG/BMP из TestFolder либо выбранный результат Wikimedia.\n'
                  'Standard пока не подключён. Ultra Free не использует LLM или API-ключи.').pack(anchor='w', pady=12)
        self.logview = tk.Text(logs, state='disabled', wrap='word')
        self.logview.pack(fill='both', expand=True)
        window.protocol('WM_DELETE_WINDOW', self.close)
        window.after(100, self.poll)

    @staticmethod
    def write(widget, text):
        widget.configure(state='normal')
        widget.delete('1.0', 'end')
        widget.insert('1.0', text)
        widget.configure(state='disabled')

    def new_chat(self):
        dialog = tk.Toplevel(self.window)
        dialog.title('Новый чат')
        dialog.transient(self.window)
        dialog.grab_set()
        ttk.Label(dialog, text='Выберите специализацию нового чата', padding=12).pack()
        colors = {'code': '#f3c342', 'photo': '#36c878', 'text': '#65a3ff', 'analysis': '#b58cf7'}
        for kind, data in CHAT_TYPES.items():
            line = ttk.Frame(dialog, padding=4)
            line.pack(fill='x', padx=14)
            tk.Label(line, text=data['marker'], fg=colors[kind],
                     font=('Segoe UI Symbol', 16)).pack(side='left')
            ttk.Button(line, text=data['label'], command=lambda k=kind: (
                self.runtime.call('new_chat', k), dialog.destroy())).pack(side='left', padx=8)
        ttk.Button(dialog, text='Отмена', command=dialog.destroy).pack(pady=10)

    def select_chat(self):
        selected = self.chatlist.curselection()
        if not selected:
            return
        self.active_chat = None if selected[0] == 0 else self.chats[selected[0] - 1]['id']
        self.filter_history()

    def filter_history(self, previous_id=None):
        self.tasks = [t for t in self.all_tasks if t.get('chat_id') == self.active_chat]
        self.tasklist.delete(0, 'end')
        for task in self.tasks:
            self.tasklist.insert('end', f"{task['state']} · {task['input'][:85]}")
        if self.tasks:
            idx = next((i for i, t in enumerate(self.tasks) if t['id'] == previous_id), 0)
            self.tasklist.selection_set(idx)
            self.render_task()
        else:
            self.write(self.output, 'В этом чате ещё нет сообщений.')
            self.images = []
            self.image_choice['values'] = []
            self.image_choice.set('')

    def selected_task(self):
        selected = self.tasklist.curselection()
        return self.tasks[selected[0]] if selected and selected[0] < len(self.tasks) else None

    def render_task(self):
        task = self.selected_task()
        if not task:
            return
        body = '\n\n'.join(m['agent'] + ': ' + m['text'] for m in task['messages'])
        if task.get('result'):
            body += '\n\n' + json.dumps(task['result'], ensure_ascii=False, indent=2)
        self.write(self.output, task['input'] + '\n' + task['id'] + '\n\n' + body)
        self.images = (task.get('result') or {}).get('images', [])
        self.image_choice['values'] = [i['title'] for i in self.images]
        if self.images:
            self.image_choice.current(0)
        else:
            self.image_choice.set('')

    def submit(self, text=None):
        if self.submitting:
            return
        text = text if text is not None else self.input.get().strip()
        if text:
            self.submitting = True
            self.runtime.call('submit', text, self.mode.get(), str(uuid.uuid4()), self.active_chat)

    def decide(self, approve):
        task = self.selected_task()
        if task and task['state'] == 'WAITING_APPROVAL':
            if not approve or messagebox.askyesno('Подтверждение действия', task['messages'][-1]['text']):
                self.runtime.call('approval', task['id'], approve)

    def cancel(self):
        task = self.selected_task()
        if task:
            self.runtime.call('cancel', task['id'])

    def wallpaper(self):
        index = self.image_choice.current()
        if 0 <= index < len(self.images):
            self.submit('установи обои image:' + self.images[index]['id'])

    def preview_image(self):
        index = self.image_choice.current()
        if 0 <= index < len(self.images):
            if messagebox.askyesno('Открыть изображение', 'Открыть выбранное изображение Wikimedia в браузере?'):
                webbrowser.open(self.images[index]['url'])

    def manual_chatgpt(self):
        self.window.clipboard_clear()
        self.window.clipboard_append(self.input.get())
        webbrowser.open('https://chatgpt.com/')

    def pair_decide(self, approve):
        selected = self.pairlist.curselection()
        if selected:
            self.runtime.call('pair_decide', self.pending[selected[0]]['id'], approve)

    def revoke(self):
        selected = self.devicelist.curselection()
        if selected:
            self.runtime.call('revoke', self.devices[selected[0]]['id'])

    def snapshot(self, data):
        self.agent_status.set(' · '.join(a['name'] + ': ' + a['state'] for a in data['agents']))
        previous = self.selected_task()
        previous_id = previous['id'] if previous else None
        self.all_tasks = data['tasks']
        self.chats = data.get('chats', [])
        self.chatlist.delete(0, 'end')
        self.chatlist.insert('end', 'Старые задачи (без чата)')
        for item in self.chats:
            self.chatlist.insert('end', item['title'] + ' · ' + item['id'][:8])
        idx = next((i + 1 for i, c in enumerate(self.chats) if c['id'] == self.active_chat), 0)
        if idx == 0:
            self.active_chat = None
        self.chatlist.selection_set(idx)
        self.filter_history(previous_id)
        self.pending, self.devices = data['pending'], data['devices']
        for widget, values in ((self.pairlist, [p['name'] for p in self.pending]),
                               (self.devicelist, [d['name'] + (' · подключён' if d['active'] else ' · отозван') for d in self.devices])):
            selection = widget.curselection()
            widget.delete(0, 'end')
            for value in values:
                widget.insert('end', value)
            if selection and selection[0] < len(values):
                widget.selection_set(selection[0])
        for name, value in data['permissions'].items():
            self.permissions[name].set(value)
        self.write(self.logview, '\n'.join(str(row) for row in data['logs']))

    def poll(self):
        try:
            while True:
                action, value = self.runtime.messages.get_nowait()
                if action == 'snapshot':
                    if value != self.last_snapshot:
                        self.snapshot(value)
                        self.last_snapshot = value
                elif action == 'new_chat':
                    self.active_chat = value['id']
                    self.input.delete(0, 'end')
                    self.input.insert(0, CHAT_TYPES[value['kind']]['prompt'])
                    self.runtime.call('snapshot')
                elif action == 'pair_code':
                    self.code.set('Одноразовый код: ' + value)
                elif action == 'submit':
                    self.submitting = False
                    if self.input.get().strip() == value['input']:
                        self.input.delete(0, 'end')
                elif action == 'error':
                    self.submitting = False
                    messagebox.showerror('VanyaBridge', value)
        except queue.Empty:
            pass
        if self.runtime.thread.is_alive():
            self.runtime.call('snapshot')
        else:
            self.status.set('Сервер остановлен — перезапустите приложение')
        self.window.after(700, self.poll)

    def close(self):
        self.runtime.stop()
        self.window.destroy()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1', help='Конкретный LAN IPv4; по умолчанию только этот ПК')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--data', default=None)
    args = parser.parse_args()
    window = tk.Tk()
    runtime = None
    try:
        runtime = Runtime(args.host, args.port, args.data)
        runtime.start()
        Desktop(window, runtime)
        window.mainloop()
    except (ValueError, OSError, RuntimeError) as error:
        messagebox.showerror('VanyaBridge: ошибка запуска', str(error))
    finally:
        if runtime is not None:
            runtime.stop()


if __name__ == '__main__':
    main()
