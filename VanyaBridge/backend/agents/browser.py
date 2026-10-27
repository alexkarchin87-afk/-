import asyncio
import ipaddress
import os
import socket
import uuid
import webbrowser
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from backend.agents.system import check_image


async def public_https(url: str):
    parts = urlsplit(url)
    if parts.scheme != 'https' or not parts.hostname or parts.username or parts.password or parts.port not in (None, 443):
        raise ValueError('Разрешены только публичные HTTPS URL без учётных данных')
    records = await asyncio.get_running_loop().getaddrinfo(parts.hostname, 443, type=socket.SOCK_STREAM)
    if not records or any(not ipaddress.ip_address(r[4][0]).is_global for r in records):
        raise ValueError('Локальные и служебные сетевые адреса запрещены')
    return url


class BrowserAgent:
    def __init__(self, root: Path):
        self.root = root
        self.images = {}  # Opaque ids, trusted source URL never interpreted as instructions.

    async def execute(self, action, argument):
        if action == 'open_url':
            await public_https(argument)
            if os.name != 'nt':
                raise ValueError('Открытие браузера на ПК требует Windows')
            if not webbrowser.open(argument, new=2):
                raise ValueError('Система не приняла запрос открытия браузера')
            return {'text': 'Открытие сайта запрошено; проверьте браузер ПК', 'status': 'REQUESTED'}
        if action == 'search_images':
            async with httpx.AsyncClient(timeout=20, follow_redirects=False, trust_env=False) as client:
                response = await client.get('https://commons.wikimedia.org/w/api.php', params={
                    'action': 'query', 'format': 'json', 'generator': 'search', 'gsrnamespace': '6',
                    'gsrsearch': argument, 'gsrlimit': '3', 'prop': 'imageinfo',
                    'iiprop': 'url|mime', 'iiurlwidth': '360',
                }, headers={'User-Agent': 'VanyaBridgeMVP/0.1 (local desktop image search)'})
                response.raise_for_status()
                if len(response.content) > 1_000_000:
                    raise ValueError('Слишком большой ответ поискового источника')
                pages = response.json().get('query', {}).get('pages', {})
            results = []
            for page in pages.values():
                info = (page.get('imageinfo') or [{}])[0]
                url = info.get('url', '')
                if (urlsplit(url).hostname != 'upload.wikimedia.org' or urlsplit(url).scheme != 'https'
                        or info.get('mime') not in ('image/jpeg', 'image/png')):
                    continue
                iid = uuid.uuid4().hex
                item = {'id': iid, 'title': page.get('title', '')[:300], 'url': url,
                        'source': info.get('descriptionurl', ''), 'mime': info['mime']}
                self.images[iid] = item
                results.append(item)
            # Bound memory, retaining only the newest 100 search results.
            self.images = dict(list(self.images.items())[-100:])
            return {'text': f'Wikimedia Commons: найдено {len(results)} изображений. Проверьте источник и лицензию.',
                    'images': results}
        if action == 'read_page':
            # MVP read automation is deliberately constrained to independently controlled sites.
            # This also prevents attacker-controlled DNS rebinding between validation and Chromium.
            allowed_hosts = {'en.wikipedia.org', 'ru.wikipedia.org', 'commons.wikimedia.org', 'www.youtube.com'}
            if urlsplit(argument).hostname not in allowed_hosts:
                raise ValueError('Чтение страниц разрешено только для Wikipedia, Wikimedia Commons и YouTube')
            await public_https(argument)
            # Lazy import/launch: ordinary chat, file operations and URL opening don't run Chromium.
            from playwright.async_api import async_playwright
            async with async_playwright() as playwright:
                browser = await playwright.chromium.launch(headless=True)
                try:
                    context = await browser.new_context(accept_downloads=False, java_script_enabled=False,
                                                        service_workers='block')
                    async def guard(route):
                        try:
                            if route.request.resource_type != 'document':
                                return await route.abort()
                            if urlsplit(route.request.url).hostname not in allowed_hosts:
                                return await route.abort()
                            await public_https(route.request.url)
                            await route.continue_()
                        except (ValueError, OSError):
                            await route.abort()
                    await context.route('**/*', guard)
                    page = await context.new_page()
                    response = await page.goto(argument, timeout=15000, wait_until='domcontentloaded')
                    if response is None or not response.ok:
                        raise ValueError('Страница недоступна')
                    text = (await page.locator('body').inner_text(timeout=5000))[:12000]
                    return {'text': text, 'untrusted': True, 'source': argument}
                finally:
                    await browser.close()
        raise ValueError('Неизвестный инструмент браузера')

    async def download_selection(self, iid):
        item = self.images.get(iid)
        if not item:
            raise ValueError('Изображение не найдено в текущем поиске; повторите поиск')
        suffix = '.jpg' if item['mime'] == 'image/jpeg' else '.png'
        target = self.root / ('vanyabridge-' + uuid.uuid4().hex + suffix)
        temp = target.with_suffix('.partial')
        total = 0
        try:
            async with httpx.AsyncClient(timeout=20, follow_redirects=False, trust_env=False) as client:
                async with client.stream('GET', item['url']) as response:
                    response.raise_for_status()
                    with temp.open('xb') as file:
                        async for block in response.aiter_bytes():
                            total += len(block)
                            if total > 15 * 1024 * 1024:
                                raise ValueError('Изображение больше 15 МиБ')
                            file.write(block)
            temp.rename(target)
            check_image(target)
            return target.name
        except BaseException:
            target.unlink(missing_ok=True)
            raise
        finally:
            temp.unlink(missing_ok=True)
