package com.voiceagent.oneplus13.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceagent.oneplus13.ui.SparkIconPro

/**
 * Экран-замок перед основным UI. Показывается, пока [signedInEmail] == null.
 * Не блокирует голосовой сервис или оверлей на уровне системы (это невозможно
 * и не нужно для персонального ассистента) — блокирует именно открытие
 * приложения, чтобы кто-то, кто просто взял телефон в руки, не полез в
 * настройки/секреты VoiceAgent через сам экран приложения.
 */
@Composable
fun AccountGateScreen(
    signedInEmail: String?,
    errorMessage: String? = null,
    onSignInClick: () -> Unit,
    onContinueClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onSkipClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SparkIconPro(size = 72.dp)
            Spacer(modifier = Modifier.height(20.dp))
            Text("VoiceAgent", fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            Spacer(modifier = Modifier.height(8.dp))

            if (signedInEmail == null) {
                Text(
                    "Персональный ассистент с доступом к твоим сообщениям, файлам и " +
                        "секретам — вход нужен, чтобы им не мог пользоваться кто-то другой, " +
                        "если телефон попадёт не в те руки.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onSignInClick) {
                    Text("Войти через Google")
                }
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(errorMessage, color = Color(0xFFC62828), textAlign = TextAlign.Center, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    // Это личный телефон одного человека, а не многопользовательский сервис —
                    // если Google-вход не настроен (например, ещё не зарегистрирован SHA-1
                    // сборки в Google Cloud Console), лучше пустить владельца в его же
                    // приложение с предупреждением, чем намертво запереть снаружи.
                    TextButton(onClick = onSkipClick) {
                        Text("Продолжить без входа (настрою вход позже)", color = Color.Gray)
                    }
                }
            } else {
                Text("Вы вошли как", color = Color.Gray)
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF3F3F3))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(signedInEmail, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onContinueClick) {
                    Text("Продолжить")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onSignOutClick) {
                    Text("Выйти и сменить аккаунт", color = Color.Gray)
                }
            }
        }
    }
}
