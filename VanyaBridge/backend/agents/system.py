import asyncio
import ctypes
import os
import platform
import shutil
import subprocess
from pathlib import Path

from PIL import Image

from backend.security.paths import safe_file, Denied


def check_image(path: Path):
    if path.suffix.lower() not in ('.jpg', '.jpeg', '.png', '.bmp'):
        raise Denied('Обои: разрешены JPG, PNG и BMP')
    if path.stat().st_size > 15 * 1024 * 1024:
        raise Denied('Изображение больше 15 МиБ')
    with Image.open(path) as image:
        if image.format not in ('JPEG', 'PNG', 'BMP') or image.width * image.height > 20_000_000:
            raise Denied('Неподдерживаемое изображение или больше 20 Мп')
        image.verify()


class SystemAgent:
    def __init__(self, root: Path):
        self.root = root
        self.allowed_apps = {}
        # Fixed known installation paths. Never execute a command string from chat or PATH.
        if os.name == 'nt':
            for base in (os.environ.get('LOCALAPPDATA'), os.environ.get('ProgramFiles')):
                if base:
                    for rel in ('Programs/Microsoft VS Code/Code.exe', 'Microsoft VS Code/Code.exe'):
                        candidate = Path(base) / rel
                        if candidate.is_file():
                            self.allowed_apps['vscode'] = candidate.resolve()

    async def execute(self, action, argument):
        if action == 'list_files':
            files = []
            for path in sorted(self.root.iterdir(), key=lambda p: p.name.casefold()):
                try:
                    checked = safe_file(self.root, path.name)
                    files.append({'name': checked.name, 'bytes': checked.stat().st_size})
                except (ValueError, OSError):
                    continue
                if len(files) >= 200:
                    break
            return {'files': files, 'limit': 200, 'text': f'Файлов: {len(files)} (максимум 200)'}
        if action == 'status':
            disk = shutil.disk_usage(self.root)
            return {'os': platform.system(), 'free_disk_bytes': disk.free, 'text': 'Состояние компьютера получено'}
        if action == 'launch':
            executable = self.allowed_apps.get(argument)
            if not executable or not executable.is_file():
                raise ValueError('VS Code не найден в стандартном каталоге белого списка')
            if os.name != 'nt':
                raise ValueError('Запуск приложения поддерживается только на Windows')
            process = subprocess.Popen([str(executable)], shell=False, close_fds=True)
            return {'text': 'Запуск VS Code запрошен; проверьте окно на ПК', 'status': 'REQUESTED', 'pid': process.pid}
        if action in ('open_file', 'wallpaper'):
            path = safe_file(self.root, argument)
            if action == 'open_file':
                if path.suffix.lower() not in ('.txt', '.pdf', '.png', '.jpg', '.jpeg', '.bmp'):
                    raise Denied('Открытие этого типа файла запрещено')
                if os.name != 'nt':
                    raise ValueError('Открытие файла поддерживается только на Windows')
                os.startfile(str(path))
                return {'text': 'Открытие файла запрошено; проверьте ПК', 'status': 'REQUESTED'}
            check_image(path)
            if os.name != 'nt':
                raise ValueError('Смена обоев поддерживается только на Windows')
            # No await between final path check and effect: cancellation cannot interleave here.
            result = ctypes.windll.user32.SystemParametersInfoW(20, 0, str(path), 3)
            if not result:
                raise ValueError('Windows отклонила смену обоев')
            return {'text': 'Windows подтвердила смену обоев; проверьте рабочий стол', 'status': 'APPLIED'}
        raise Denied('Действие не входит в белый список')
