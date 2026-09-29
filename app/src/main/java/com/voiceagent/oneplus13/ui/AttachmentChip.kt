package com.voiceagent.oneplus13.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Карточка готового .zip внутри чата — ровно то, о чём просили: сгенерированный
 * проект не нужно искать в Загрузках вручную, тут же можно открыть или
 * переслать (в Telegram, Termux и т.п.), как с файлом от Claude.
 * Используется и в [AgentSiriPanel], и в [WindowsCornerPopup] (а значит и в
 * системном оверлее — он переиспользует тот же composable).
 */
@Composable
fun AttachmentChip(label: String, path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.06f))
            .clickable { openZipFile(context, path) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📦", fontSize = 14.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "Поделиться",
            fontSize = 11.sp,
            color = Color.Gray,
            modifier = Modifier.clickable { shareZipFile(context, path) }
        )
    }
}

/** Открыть zip тем, что установлено в системе (обычно файловый менеджер
 *  умеет заглянуть внутрь). Если открыть нечем — сразу падаем в "Поделиться". */
fun openZipFile(context: Context, path: String) {
    val uri = zipUri(context, path) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/zip")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { shareZipFile(context, path) }
}

/** Системная шторка "Поделиться" — отправить архив в Telegram, Termux,
 *  почту и т.п. Работает и из Activity, и из Service (оверлей-пузырь). */
fun shareZipFile(context: Context, path: String) {
    val uri = zipUri(context, path) ?: return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "Поделиться проектом").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

private fun zipUri(context: Context, path: String) = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
}.getOrNull()
