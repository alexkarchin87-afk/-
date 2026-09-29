package com.voiceagent.oneplus13.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceagent.oneplus13.R

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    /** Путь к .zip сгенерированного проекта, если это сообщение о готовом
     *  проекте (см. AgentEvent.ProjectReady) — тогда под текстом рисуется
     *  карточка "Открыть/Поделиться". Для обычных сообщений — null. */
    val attachmentPath: String? = null,
    val attachmentLabel: String? = null
)

/**
 * Панель чата агента — тот же брендинг ("Ваня", шапка со статусом, приветствие,
 * микрофон и стрелка отправки), что и у [WindowsCornerPopup], просто в двух
 * других раскладках:
 *
 * На телефоне ([dockedMode] = false) — классическая шторка, выезжающая
 * снизу поверх всего экрана с затемнением (как у Siri).
 *
 * На планшете/ПК ([dockedMode] = true) — стационарная боковая колонка,
 * без затемнения фона и без анимации "снизу вверх": вызывающий код сам
 * решает, где её разместить (см. DesktopLayout в MainScreen.kt).
 */
@Composable
fun AgentSiriPanel(
    isVisible: Boolean,
    messages: List<ChatMessage>,
    quickPrompts: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onQuickPrompt: (String) -> Unit = {},
    dockedMode: Boolean = false,
    isListening: Boolean = false,
    maxWidth: Dp? = null
) {
    if (dockedMode) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            PanelContent(
                messages = messages,
                quickPrompts = quickPrompts,
                onSend = onSend,
                onQuickPrompt = onQuickPrompt,
                onDismiss = onDismiss,
                isListening = isListening,
                modifier = Modifier.fillMaxHeight(),
                showDragHandle = false
            )
        }
        return
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(250)
                )
            ) {
                val widthModifier = if (maxWidth != null) {
                    Modifier.widthIn(max = maxWidth).fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth()
                }

                PanelContent(
                    messages = messages,
                    quickPrompts = quickPrompts,
                    onSend = onSend,
                    onQuickPrompt = onQuickPrompt,
                    onDismiss = onDismiss,
                    isListening = isListening,
                    // не даём тапу по самой панели закрывать её (см. clickable(onClick = onDismiss) выше)
                    modifier = widthModifier
                        .fillMaxHeight(0.7f)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Color.White)
                        .clickable(enabled = false) {},
                    showDragHandle = true
                )
            }
        }
    }
}

@Composable
private fun PanelContent(
    messages: List<ChatMessage>,
    quickPrompts: List<String>,
    onSend: (String) -> Unit,
    onQuickPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
    isListening: Boolean,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true
) {
    var input by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .background(Color.White)
            .padding(bottom = 8.dp)
    ) {
        if (showDragHandle) {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = 0.15f))
            )
        }

        PanelHeader(isListening = isListening, onDismiss = onDismiss)

        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyStateBody()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { message ->
                        ChatBubble(message)
                    }
                }
            }
        }

        if (quickPrompts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPrompts.forEach { prompt ->
                    AssistChip(
                        onClick = { onQuickPrompt(prompt) },
                        label = { Text(prompt) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Напишите сообщение") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                trailingIcon = { MicGlyph(size = 20.dp) },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedBorderColor = Color.Black
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank()) {
                            onSend(input.trim())
                            input = ""
                        }
                    }
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        onSend(input.trim())
                        input = ""
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
            ) {
                SendArrowGlyph(size = 18.dp)
            }
        }
    }
}

@Composable
private fun PanelHeader(isListening: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SparkIconPro(size = 32.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.assistant_role_label),
                fontSize = 11.sp,
                color = Color.Gray
            )
            Text(
                text = stringResource(R.string.assistant_display_name),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isListening) Color(0xFF34C759) else Color(0xFFBDBDBD))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.assistant_ready_label),
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            CloseGlyph(size = 14.dp, color = Color.Black.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun EmptyStateBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.assistant_greeting),
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.assistant_greeting_hint),
            fontSize = 14.sp,
            color = Color.Gray,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 16.dp
                    )
                )
                .background(if (message.isUser) Color.Black else Color(0xFFF0F0F0))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text = message.text,
                    color = if (message.isUser) Color.White else Color.Black
                )
                if (message.attachmentPath != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AttachmentChip(
                        label = message.attachmentLabel ?: "project.zip",
                        path = message.attachmentPath
                    )
                }
            }
        }
    }
}
