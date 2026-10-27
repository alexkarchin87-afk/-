"""Windows and network boundaries mocked explicitly; not a real Windows acceptance test."""
import asyncio
import io
from types import SimpleNamespace

import httpx
import pytest
from PIL import Image

import backend.agents.browser as browser_module
import backend.agents.system as system_module
from backend.agents.browser import BrowserAgent
from backend.agents.system import SystemAgent


def test_windows_vscode_fixed_executable_no_shell(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(system_module, 'os', SimpleNamespace(name='nt', environ={}))
    monkeypatch.setattr(system_module.subprocess, 'Popen', lambda args, **kw: calls.append((args, kw)) or SimpleNamespace(pid=123))
    agent = SystemAgent(tmp_path)
    exe = tmp_path / 'Code.exe'; exe.write_bytes(b'test fixture')
    agent.allowed_apps['vscode'] = exe
    result = asyncio.run(agent.execute('launch', 'vscode'))
    assert result['status'] == 'REQUESTED'
    assert calls == [([str(exe)], {'shell': False, 'close_fds': True})]
    with pytest.raises(ValueError): asyncio.run(agent.execute('launch', 'powershell -Command anything'))


def test_windows_wallpaper_checks_native_result(tmp_path, monkeypatch):
    image = tmp_path / 'wallpaper.png'; Image.new('RGB', (3, 3)).save(image)
    calls = []
    monkeypatch.setattr(system_module, 'os', SimpleNamespace(name='nt', environ={}))
    monkeypatch.setattr(system_module.ctypes, 'windll', SimpleNamespace(user32=SimpleNamespace(
        SystemParametersInfoW=lambda *args: calls.append(args) or 1)), raising=False)
    agent = SystemAgent(tmp_path)
    result = asyncio.run(agent.execute('wallpaper', image.name))
    assert result['status'] == 'APPLIED'
    assert calls == [(20, 0, str(image), 3)]
    monkeypatch.setattr(system_module.ctypes.windll.user32, 'SystemParametersInfoW', lambda *args: 0)
    with pytest.raises(ValueError, match='Windows'): asyncio.run(agent.execute('wallpaper', image.name))


def test_windows_open_executable_is_forbidden(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(system_module, 'os', SimpleNamespace(name='nt', environ={}, startfile=calls.append))
    (tmp_path / 'test.cmd').write_text('dangerous')
    agent = SystemAgent(tmp_path)
    with pytest.raises(ValueError): asyncio.run(agent.execute('open_file', 'test.cmd'))
    assert calls == []


def test_windows_url_requested_not_claimed_open(tmp_path, monkeypatch):
    calls = []
    async def valid(url): return url
    monkeypatch.setattr(browser_module, 'public_https', valid)
    monkeypatch.setattr(browser_module, 'os', SimpleNamespace(name='nt'))
    monkeypatch.setattr(browser_module.webbrowser, 'open', lambda url, **kw: calls.append(url) or True)
    result = asyncio.run(BrowserAgent(tmp_path).execute('open_url', 'https://www.youtube.com/'))
    assert calls == ['https://www.youtube.com/'] and result['status'] == 'REQUESTED'


def use_transport(monkeypatch, handler):
    original = httpx.AsyncClient
    monkeypatch.setattr(browser_module.httpx, 'AsyncClient', lambda **kw: original(transport=httpx.MockTransport(handler), **kw))


def test_search_parses_source_results_and_filters_untrusted_hosts(tmp_path, monkeypatch):
    def handler(request):
        assert request.url.host == 'commons.wikimedia.org'
        return httpx.Response(200, json={'query': {'pages': {
            '1': {'title': 'File:Mountains.jpg', 'imageinfo': [{'url': 'https://upload.wikimedia.org/mountain.jpg', 'mime': 'image/jpeg'}]},
            '2': {'title': 'File:Evil.jpg', 'imageinfo': [{'url': 'https://127.0.0.1/private.jpg', 'mime': 'image/jpeg'}]},
        }}})
    use_transport(monkeypatch, handler)
    result = asyncio.run(BrowserAgent(tmp_path).execute('search_images', 'mountains'))
    assert len(result['images']) == 1 and result['images'][0]['title'] == 'File:Mountains.jpg'


def test_selected_image_download_and_verify(tmp_path, monkeypatch):
    body = io.BytesIO(); Image.new('RGB', (4, 4)).save(body, format='PNG')
    use_transport(monkeypatch, lambda _: httpx.Response(200, content=body.getvalue()))
    agent = BrowserAgent(tmp_path)
    agent.images['selected'] = {'url': 'https://upload.wikimedia.org/test.png', 'mime': 'image/png'}
    name = asyncio.run(agent.download_selection('selected'))
    assert (tmp_path / name).is_file()
    assert not list(tmp_path.glob('*.partial'))


def test_download_error_cleans_partial_file(tmp_path, monkeypatch):
    use_transport(monkeypatch, lambda _: httpx.Response(200, content=b'not a real image'))
    agent = BrowserAgent(tmp_path)
    agent.images['selected'] = {'url': 'https://upload.wikimedia.org/test.png', 'mime': 'image/png'}
    with pytest.raises(OSError): asyncio.run(agent.download_selection('selected'))
    assert list(tmp_path.iterdir()) == []


def test_read_requires_allowed_site(tmp_path):
    with pytest.raises(ValueError):
        asyncio.run(BrowserAgent(tmp_path).execute('read_page', 'https://attacker.example/'))
