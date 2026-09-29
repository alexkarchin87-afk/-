package com.voiceagent.oneplus13.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceagent.oneplus13.R

/**
 * Компактная всплывающая карточка для [DeviceFormFactor.WINDOWS] — вызывается
 * из угла, как Siri/Cortana на десктопе: не занимает весь экран, не затемняет
 * фон, "плавает" поверх контента в правом нижнем углу рядом с [FloatingSparkButton].
 *
 * Дизайн — по референсу: шапка с именем ассистента и статусом, приветствие,
 * пилюли быстрых команд, поле ввода с микрофоном и кнопкой отправки.
 */
@Composable
fun WindowsCornerPopup(
    isVisible: Boolean,
    isListening: Boolean,
    messages: List<ChatMessage>,
    quickPrompts: List<String>,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 340.dp
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(180)),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = width)
                .width(width)
                .shadow(elevation = 20.dp, shape = RoundedCornerShape(22.dp), clip = false)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White)
        ) {
            PopupHeader(isListening = isListening, onDismiss = onDismiss)

            if (messages.isEmpty()) {
                EmptyStateBody()
            } else {
                MessageList(messages = messages)
            }

            if (quickPrompts.isNotEmpty()) {
                QuickPromptRow(quickPrompts = quickPrompts, onQuickPrompt = onQuickPrompt)
            }

            InputRow(onSend = onSend)
        }
    }
}

@Composable
private fun PopupHeader(isListening: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SparkIconPro(size = 30.dp)
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
        StatusDot(isListening = isListening)
        Spacer(modifier = Modifier.width(10.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            CloseGlyph(size = 14.dp, color = Color.Black.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun StatusDot(isListening: Boolean) {
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
}

@Composable
private fun EmptyStateBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.assistant_greeting),
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.assistant_greeting_hint),
            fontSize = 13.sp,
            color = Color.Gray,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages) { message -> CompactChatBubble(message) }
    }
}

@Composable
private fun CompactChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (message.isUser) 14.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 14.dp
                    )
                )
                .background(if (message.isUser) Color.Black else Color(0xFFF0F0F0))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = message.text,
                    color = if (message.isUser) Color.White else Color.Black,
                    fontSize = 13.sp
                )
                if (message.attachmentPath != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    AttachmentChip(
                        label = message.attachmentLabel ?: "project.zip",
                        path = message.attachmentPath
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPromptRow(quickPrompts: List<String>, onQuickPrompt: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quickPrompts.take(2).forEach { prompt ->
            AssistChip(
                onClick = { onQuickPrompt(prompt) },
                label = { Text(prompt, fontSize = 12.sp) },
                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFF3F3F3))
            )
        }
    }
}

@Composable
private fun InputRow(onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Напишите сообщение", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            trailingIcon = { MicGlyph(size = 18.dp) },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color(0xFFE0E0E0),
                focusedBorderColor = Color.Black
            ),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
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
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black)
        ) {
            SendArrowGlyph(size = 16.dp)
        }
    }
}
