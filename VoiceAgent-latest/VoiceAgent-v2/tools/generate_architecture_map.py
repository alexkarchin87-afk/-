#!/usr/bin/env python3
"""Deterministic Kotlin/Gradle source index; semantics and ownership are explicit JSON.

This is a lexical architecture guard, not a Kotlin compiler or runtime call graph.
Only literal include/project declarations in Kotlin Gradle scripts are supported.
"""
import argparse
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'com.voiceagent.oneplus13'


def strip_comments(text):
    # Keep quoted strings intact so // inside URLs is not a comment.
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'/\'|/\*[\s\S]*?\*/|//[^\n]*'
    return re.sub(pattern, lambda m: '' if m[0].startswith(('/*', '//')) else m[0], text)


def code_only(text):
    return re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"', '""', strip_comments(text))


def cycles(graph):
    """Strongly connected components, including self edges."""
    index, low, stack, active, found = {}, {}, [], set(), []
    def visit(node):
        index[node] = low[node] = len(index)
        stack.append(node); active.add(node)
        for target in sorted(graph.get(node, ())):
            if target not in index:
                visit(target); low[node] = min(low[node], low[target])
            elif target in active:
                low[node] = min(low[node], index[target])
        if low[node] == index[node]:
            group = []
            while True:
                item = stack.pop(); active.remove(item); group.append(item)
                if item == node: break
            if len(group) > 1 or node in graph.get(node, ()):
                found.append(sorted(group))
    for node in sorted(graph):
        if node not in index: visit(node)
    return sorted(found)


def inspect(root):
    errors = []
    registry = json.loads((root / 'architecture/components.json').read_text())
    settings = strip_comments((root / 'settings.gradle.kts').read_text())
    modules = sorted(set(re.findall(r'include\("(:[^\"]+)"\)', settings)))
    if not modules: errors.append('No literal Gradle modules found')
    module_dirs = {m: m[1:].replace(':', '/') for m in modules}
    graph = {}
    for module, directory in module_dirs.items():
        build = root / directory / 'build.gradle.kts'
        if not build.is_file():
            errors.append(f'Missing module build file: {build}'); continue
        deps = set(re.findall(r'project\("(:[^\"]+)"\)', strip_comments(build.read_text())))
        graph[module] = deps
        for dep in deps - set(modules): errors.append(f'Unknown Gradle dependency: {module} -> {dep}')
    for cycle in cycles(graph): errors.append('Gradle cycle: ' + ' -> '.join(cycle))
    sources, tests = {}, set()
    for module, directory in module_dirs.items():
        for source in sorted((root / directory / 'src').glob('**/*.kt')):
            path = source.relative_to(root).as_posix()
            if '/src/main/' not in path:
                tests.add(path); continue
            text = code_only(source.read_text())
            package = re.search(r'^package\s+([\w.]+)', text, re.M)
            if not package: errors.append(f'Missing package: {path}'); continue
            symbols = re.findall(r'^(?:(?:public|internal|private|data|sealed|enum|abstract|open|suspend|inline|value|annotation)\s+)*(?:class|object|interface|fun)\s+(?:[\w<>?.]+\.)?(\w+)', text, re.M)
            sources[path] = dict(module=module, package=package[1], symbols=sorted(set(symbols)), code=text)
    owners = {}
    components = {}
    for component in registry['components']:
        cid = component['id']
        if cid in components: errors.append(f'Duplicate component id: {cid}')
        components[cid] = component
        for path in component['files']:
            if path in owners: errors.append(f'Duplicate owner: {path}: {owners[path]}, {cid}')
            owners[path] = cid
            if path not in sources: errors.append(f'Missing production source: {path}')
        for path in [component['readme'], *component['tests']]:
            if not (root / path).is_file(): errors.append(f'Missing reference: {path}')
        for path in component['tests']:
            if path not in tests: errors.append(f'Not an actual Kotlin test: {path}')
    for path in sources.keys() - owners.keys(): errors.append(f'Unowned source: {path}')
    # Check local Markdown links in component guides; generated map is checked
    # byte-for-byte by --check, so stale links there cannot be accepted either.
    for component in components.values():
        guide = root / component['readme']
        if not guide.is_file(): continue
        for destination in re.findall(r'\[[^\]]*\]\(([^)]+)\)', guide.read_text()):
            url = urlsplit(destination.strip('<>'))
            if url.scheme or not url.path: continue
            target = (guide.parent / unquote(url.path)).resolve()
            if not target.is_relative_to(root.resolve()) or not target.is_file():
                errors.append(f'Missing Markdown link: {component["readme"]} -> {destination}')
    registered_tests = {p for c in components.values() for p in c['tests']}
    for path in tests - registered_tests: errors.append(f'Unindexed test: {path}')
    symbols = {}
    packages = {}
    for path, source in sources.items():
        packages.setdefault(source['package'], []).append(path)
        for symbol in source['symbols']:
            symbols.setdefault(source['package'] + '.' + symbol, set()).add(path)
    edges = {cid: set() for cid in components}
    for path, source in sources.items():
        code = source['code']
        deps = set()
        imports = re.findall(r'^import\s+([\w.*]+)', code, re.M)
        for imp in imports:
            if imp.endswith('.*'): deps.update(packages.get(imp[:-2], []))
            else:
                # Nested members may import a class's member rather than the class.
                while imp.startswith(PREFIX):
                    deps.update(symbols.get(imp, ()))
                    if '.' not in imp: break
                    imp = imp.rsplit('.', 1)[0]
        # Same-package uses and fully qualified references are also boundary edges.
        tokens = set(re.findall(r'\b[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*', code))
        words = {word for token in tokens for word in token.split('.')}
        for token in tokens:
            while token.startswith(PREFIX):
                deps.update(symbols.get(token, ()))
                token = token.rsplit('.', 1)[0] if '.' in token else ''
        for sibling in packages[source['package']]:
            if words.intersection(sources[sibling]['symbols']): deps.add(sibling)
        deps.discard(path)
        source['deps'] = sorted(deps)
        owner = owners.get(path)
        for dep in deps:
            target = owners.get(dep)
            if owner and target and owner != target: edges[owner].add(target)
            if sources[dep]['module'] != source['module'] and sources[dep]['module'] not in graph.get(source['module'], ()):
                errors.append(f'Undeclared module dependency: {path} -> {dep}')
    for cid, targets in edges.items():
        allowed = set(components[cid]['allowed_dependencies'])
        for target in allowed - components.keys(): errors.append(f'Unknown allowed component: {cid} -> {target}')
        for target in targets - allowed: errors.append(f'Forbidden boundary: {cid} -> {target}')
    entries = []
    ns = '{http://schemas.android.com/apk/res/android}'
    for module, directory in module_dirs.items():
        manifest = root / directory / 'src/main/AndroidManifest.xml'
        if not manifest.exists(): continue
        for element in ET.parse(manifest).iter():
            name = element.get(ns + 'name', '')
            if element.tag not in ('application', 'activity', 'service', 'receiver', 'provider') or not name: continue
            qualified = PREFIX + name if name.startswith('.') else name
            paths = sorted(symbols.get(qualified, ()))
            if qualified.startswith(PREFIX) and not paths: errors.append(f'Manifest class absent: {qualified}')
            entries.append((module, element.tag, qualified, paths))
    return registry, modules, graph, sources, owners, edges, entries, errors


def link(path):
    return f'[{path}]({path})'


def render(data):
    registry, modules, graph, sources, owners, edges, entries, _ = data
    components = {c['id']: c for c in registry['components']}
    lines = ['# Карта архитектуры VoiceAgent-v2', '',
        'Генерируется `python3 tools/generate_architecture_map.py`. Семантика и владельцы: ' + link('architecture/components.json') + '.', '',
        'Это индекс фактических исходников, не гарантия работы на устройстве. Зависимости — лексические ссылки Kotlin: imports, текущий package и полные имена; reflection, строки и динамические вызовы не анализируются. Wildcard import учитывается консервативно. Столбец «Тесты» — явно зарегистрированные тесты подсистемы, не измеренное покрытие каждого класса.', '',
        '## Быстрый поиск', '', '| Функция | Модуль | Класс | Путь | Зависимости | Тесты |', '|---|---|---|---|---|---|']
    for cid, component in components.items():
        for path in component['files']:
            if path not in sources: continue
            src = sources[path]
            deps = ', '.join(sorted({owners.get(p, '?') for p in src['deps']})) or '—'
            lines.append('| ' + ' | '.join([component['purpose'], src['module'], ', '.join(src['symbols']) or '(top-level properties)', link(path), deps, '<br>'.join(link(t) for t in component['tests']) or 'Нет JVM-теста']) + ' |')
    lines += ['', '## Модули', '']
    for module in modules: lines.append(f'- `{module}` → ' + (', '.join(f'`{d}`' for d in sorted(graph[module])) or 'нет project dependencies'))
    lines += ['', 'Циклы Gradle: ' + (str(cycles(graph)) if cycles(graph) else 'не обнаружены.'), '', '## Packages', '']
    for package in sorted({s['package'] for s in sources.values()}):
        lines.append(f'- `{package}`: ' + ', '.join(sorted({s['module'] for s in sources.values() if s['package'] == package})))
    lines += ['', '## Точки входа из AndroidManifest', '', '| Модуль | Тип | Компонент | Реализация |', '|---|---|---|---|']
    for module, kind, name, paths in entries: lines.append(f'| {module} | {kind} | {name} | ' + (', '.join(link(p) for p in paths) or 'Внешняя AndroidX-библиотека') + ' |')
    lines += ['', '## Владельцы и направления зависимостей', '']
    for cid, c in components.items():
        incoming = sorted(k for k, v in edges.items() if cid in v)
        lines += [f'### {cid}', '', link(c['readme']), '', f"{c['purpose']}", '', 'Входящие: ' + (', '.join(incoming) or '—') + '. Исходящие: ' + (', '.join(sorted(edges[cid])) or '—') + '.']
    lines += ['', '## Циклы между подсистемами (существующий технический долг)', '']
    for cycle in cycles(edges): lines.append('- Сильно связанная группа: ' + ', '.join(cycle))
    if not cycles(edges): lines.append('Не обнаружены.')
    lines += ['', 'Границы разрешены явно в реестре. Существующие циклы подсистем не равны циклам Gradle; новые запрещённые направления завершают проверку ошибкой.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        data = inspect(args.root)
        if data[-1]:
            print('\n'.join(data[-1]), file=sys.stderr); return 1
        content = render(data)
        target = args.root / 'ARCHITECTURE_MAP.md'
        if args.check:
            if not target.exists() or target.read_text() != content:
                print('ARCHITECTURE_MAP.md is stale; regenerate it', file=sys.stderr); return 1
        else: target.write_text(content)
        print(f'Architecture OK: {len(data[3])} sources, {len(data[1])} modules')
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(f'Architecture error: {error}', file=sys.stderr); return 1


if __name__ == '__main__': sys.exit(main())
