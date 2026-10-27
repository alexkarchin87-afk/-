"""Specialization metadata. Templates are editable drafts, not generated replies."""

CHAT_TYPES = {
    "code": {"label": "Код", "marker": "■", "prompt": "Помоги с программированием. Моя задача: "},
    "photo": {"label": "Фото", "marker": "▲", "prompt": "Составь промпт для генерации изображения. Идея: "},
    "text": {"label": "Текст", "marker": "●", "prompt": "Помоги написать или отредактировать текст. Задача: "},
    "analysis": {"label": "Анализ", "marker": "◆", "prompt": "Помоги проанализировать данные. Цель: "},
}
