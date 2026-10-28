"""Optional text-only Ollama adapter; it never executes generated instructions."""
import json
import re
import httpx

OLLAMA_URL = 'http://127.0.0.1:11434/api/chat'
MODEL = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$')

class LocalLLM:
    def __init__(self, model):
        if not MODEL.fullmatch(model) or '..' in model or model.casefold().endswith('-cloud'):
            raise ValueError('Укажите корректное имя локальной модели Ollama')
        self.model = model

    async def respond(self, messages):
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(120, connect=3), trust_env=False, follow_redirects=False) as client:
                async with client.stream('POST', OLLAMA_URL, json={
                    'model': self.model, 'messages': messages, 'stream': False,
                    'keep_alive': '0', 'options': {'num_predict': 512}
                }) as response:
                    if response.status_code == 404:
                        raise ValueError('Модель не найдена в локальном Ollama. Проверьте имя и установку модели.')
                    response.raise_for_status()
                    body = bytearray()
                    async for chunk in response.aiter_bytes():
                        body.extend(chunk)
                        if len(body) > 262144:
                            raise ValueError('Ответ локальной модели слишком большой')
            payload = json.loads(body)
            message = payload.get('message')
            if payload.get('done') is not True or not isinstance(message, dict) or message.get('role') != 'assistant':
                raise ValueError('Локальная модель вернула неполный ответ')
            content = message.get('content')
            if not isinstance(content, str) or not content.strip() or len(content) > 12000:
                raise ValueError('Локальная модель вернула пустой или слишком длинный ответ')
            return content.strip()
        except httpx.ConnectError:
            raise ValueError('Не удалось подключиться к локальному Ollama на 127.0.0.1:11434. Запустите его и проверьте модель.') from None
        except (httpx.HTTPError, json.JSONDecodeError, UnicodeDecodeError):
            raise ValueError('Локальная модель недоступна или вернула некорректный ответ') from None
