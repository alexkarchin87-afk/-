"""
Telegram-бот: принимает ссылку, отдаёт mp3 или mp4. Запускается прямо на
телефоне через Termux — отдельный сервер не нужен.

Токен НИКОГДА не хранится в этом файле — берётся из переменной окружения
TELEGRAM_BOT_TOKEN (получи у @BotFather командой /newbot, скопируй токен).

Запуск в Termux на OnePlus 13:
    pkg install python ffmpeg
    pip install -r requirements.txt
    export TELEGRAM_BOT_TOKEN=твой_токен_от_botfather
    python bot.py

Важно про авторские права: этот бот — обёртка над yt-dlp. Используй его только
для контента, на который у тебя есть права, для собственных видео/аудио или
для источников, явно разрешающих скачивание.
"""
import os
import logging
import tempfile
from pathlib import Path

import yt_dlp
from telegram import Update
from telegram.ext import Application, CommandHandler, MessageHandler, ContextTypes, filters

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("media-bot")

TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN")
if not TOKEN:
    raise SystemExit("Set TELEGRAM_BOT_TOKEN environment variable (see BotFather).")

MAX_UPLOAD_BYTES = 50 * 1024 * 1024


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "Пришли ссылку на видео. /mp3 <ссылка> — аудио, /mp4 <ссылка> или просто "
        "ссылка — видео."
    )


async def handle_link(update: Update, context: ContextTypes.DEFAULT_TYPE, audio_only: bool = False):
    text = update.message.text.strip()
    url = text.split()[-1]
    status = await update.message.reply_text("Скачиваю…")

    with tempfile.TemporaryDirectory() as tmp:
        out_template = str(Path(tmp) / "%(title).100s.%(ext)s")
        ydl_opts = {
            "outtmpl": out_template,
            "quiet": True,
            "noplaylist": True,
            "max_filesize": MAX_UPLOAD_BYTES,
        }
        if audio_only:
            ydl_opts["format"] = "bestaudio/best"
            ydl_opts["postprocessors"] = [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }]
        else:
            ydl_opts["format"] = "best[ext=mp4]/best"

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                filename = ydl.prepare_filename(info)
                if audio_only:
                    filename = str(Path(filename).with_suffix(".mp3"))
        except Exception as e:
            log.exception("download failed")
            await status.edit_text(f"Не получилось скачать: {e}")
            return

        file_path = Path(filename)
        if not file_path.exists():
            await status.edit_text("Файл не найден после скачивания.")
            return
        if file_path.stat().st_size > MAX_UPLOAD_BYTES:
            await status.edit_text("Файл больше 50 МБ — Telegram не даст боту его отправить.")
            return

        await status.edit_text("Отправляю…")
        with open(file_path, "rb") as f:
            if audio_only:
                await update.message.reply_audio(audio=f)
            else:
                await update.message.reply_video(video=f)
        await status.delete()


async def handle_mp3(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await handle_link(update, context, audio_only=True)


async def handle_mp4(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await handle_link(update, context, audio_only=False)


async def handle_plain_link(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await handle_link(update, context, audio_only=False)


def main():
    app = Application.builder().token(TOKEN).build()
    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("mp3", handle_mp3))
    app.add_handler(CommandHandler("mp4", handle_mp4))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_plain_link))
    log.info("Bot started")
    app.run_polling()


if __name__ == "__main__":
    main()
