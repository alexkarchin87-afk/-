from dataclasses import dataclass


@dataclass(frozen=True)
class Plan:
    agent: str
    action: str
    argument: str = ''
    approval: bool = False


class Planner:
    """An intentionally small grammar, not a neural network."""
    def plan(self, text: str, mode: str) -> Plan:
        if mode != 'Ultra Free':
            raise ValueError('Standard: LLM-провайдер не подключён. Выберите Ultra Free; платных запросов нет.')
        command = text.strip()
        lowered = command.casefold().rstrip('.!')
        if lowered in ('открой youtube на компьютере', 'открой youtube'):
            return Plan('BrowserAgent', 'open_url', 'https://www.youtube.com/', True)
        if lowered in ('покажи файлы в моей тестовой папке', 'покажи файлы', 'файлы'):
            return Plan('SystemAgent', 'list_files')
        if lowered in ('открой vs code', 'открой vscode'):
            return Plan('SystemAgent', 'launch', 'vscode', True)
        if lowered in ('статус компьютера', 'статус'):
            return Plan('SystemAgent', 'status')
        if lowered.startswith('найди изображения '):
            query = command[len('найди изображения '):].strip()
            if not query:
                raise ValueError('Введите поисковый запрос')
            return Plan('BrowserAgent', 'search_images', query)
        for prefix, agent, action, approval in (
            ('прочитай страницу ', 'BrowserAgent', 'read_page', False),
            ('открой сайт ', 'BrowserAgent', 'open_url', True),
            ('открой файл ', 'SystemAgent', 'open_file', True),
            ('установи обои ', 'SystemAgent', 'wallpaper', True),
        ):
            if lowered.startswith(prefix):
                argument = command[len(prefix):].strip()
                if not argument:
                    raise ValueError('Не указан аргумент команды')
                return Plan(agent, action, argument, approval)
        raise ValueError('Команда не поддерживается локальным планировщиком. Это не ответ LLM. '
                         'Попробуйте «покажи файлы», «открой VS Code», «найди изображения гор». '
                         'Можно вручную перенести запрос в официальный ChatGPT.')
