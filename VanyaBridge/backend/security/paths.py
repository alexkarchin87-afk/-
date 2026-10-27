from pathlib import Path
import stat


class Denied(ValueError):
    pass


def safe_file(root: Path, relative: str) -> Path:
    """Only existing plain files below a fixed root. Deny Windows ADS and links."""
    if not relative or any(c in relative for c in (':', '\\', '\x00')):
        raise Denied('Недопустимый путь')
    rel = Path(relative)
    if rel.is_absolute() or any(p in ('..', '.') for p in relative.split('/')):
        raise Denied('Путь вне разрешённой папки')
    base = root.resolve(strict=True)
    current = base
    for part in rel.parts:
        current = current / part
        info = current.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise Denied('Ссылки и reparse points запрещены')
    if not current.resolve(strict=True).is_relative_to(base) or not current.is_file():
        raise Denied('Разрешены только файлы внутри тестовой папки')
    return current
