package com.voiceagent.oneplus13.account

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import java.security.MessageDigest

/**
 * "Система аккаунтов" для персонального ассистента — это не многопользовательский
 * бэкенд (агент всё равно на одном телефоне, обслуживает одного человека), а
 * замок на вход в приложение через Google: если телефон потеряется или им
 * завладеет кто-то чужой, экран блокировки VoiceAgent просит войти тем же
 * Google-аккаунтом, что и в первый раз.
 *
 * Используется базовый GoogleSignInClient с requestEmail()/requestProfile() без
 * requestIdToken(). Но и такой вход НЕ работает «из коробки»: Google проверяет пару
 * «package name + SHA-1 сертификата подписи APK» (OAuth-клиент типа Android в Google Cloud
 * Console). Нет пары — ошибка 10 (DEVELOPER_ERROR). Если же APK подписан случайным debug-ключом
 * (так делает CI без стабильного keystore), SHA-1 меняется при каждой сборке. См. README
 * «Вход через Google». Для синхронизации с сервером позже: requestIdToken(serverClientId).
 *
 * Роли (Admin/User): САМЫЙ ПЕРВЫЙ аккаунт, когда-либо вошедший на этом
 * телефоне, автоматически становится Admin — это разумное умолчание для
 * личного устройства (тот, кто его настраивал). Любой другой Google-аккаунт,
 * вошедший позже (родственник одолжил телефон, вошёл своим), получает
 * User — без доступа к общим ключам API и резервной копии (см.
 * SettingsActivity: секции с ключами скрыты для не-Admin).
 */
class AccountManager(private val context: Context) {

    enum class Role { ADMIN, USER }

    private val client: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    private val prefs = AccountPrefs(context)

    /** Последний вход, восстановленный без похода в сеть — Play Services кэширует его сами. */
    fun lastSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    /** Отдаём то, что реально можно показать/сравнить сразу при старте, без ожидания callback. */
    fun ownerEmail(): String? = lastSignedInAccount()?.email ?: prefs.ownerEmail

    /** Роль ТЕКУЩЕГО вошедшего аккаунта — USER, если никто не вошёл. */
    fun currentRole(): Role {
        // Пока на телефоне никто не регистрировался (в т.ч. вход через Google не настроен и
        // владелец нажал «Продолжить без входа»), это личное устройство владельца: иначе раздел
        // с ключами API скрыт и приложение невозможно настроить вообще.
        if (prefs.adminEmails().isEmpty()) return Role.ADMIN
        val email = ownerEmail() ?: return Role.USER
        return if (prefs.isAdmin(email)) Role.ADMIN else Role.USER
    }

    /** Владелец выбрал «Продолжить без входа» — помним это, чтобы не спрашивать при каждом запуске. */
    fun isLockSkipped(): Boolean = prefs.lockSkipped
    fun setLockSkipped(skipped: Boolean) { prefs.lockSkipped = skipped }

    /** Выйти и снова показать экран входа при следующем запуске (кнопка в Настройках). */
    fun resetLock() {
        prefs.lockSkipped = false
        signOut { }
    }

    /**
     * SHA-1 сертификата, которым подписан ЭТОТ установленный APK — именно его нужно вписать
     * в Google Cloud Console. Показываем в тексте ошибки, чтобы не искать его через keytool.
     */
    @Suppress("DEPRECATION")
    fun signingSha1(): String? = runCatching {
        val pm = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        }
        signature?.let { sig ->
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                .joinToString(":") { b -> "%02X".format(b) }
        }
    }.getOrNull()

    val signInIntent: Intent get() = client.signInIntent

    /** Разобрать результат из ActivityResult после [signInIntent]. */
    fun handleSignInResult(data: Intent?): SignInResult {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            val account = task.getResult(ApiException::class.java)
            prefs.ownerEmail = account?.email
            prefs.ownerName = account?.displayName
            prefs.lockSkipped = false
            account?.email?.let { prefs.registerAsAdminIfFirstEver(it) }
            SignInResult.Success(account)
        } catch (e: ApiException) {
            // statusCode 10 = DEVELOPER_ERROR — почти всегда значит, что SHA-1
            // сертификата сборки не зарегистрирован в Google Cloud Console для
            // этого package name (см. README: "Настройка входа через Google").
            // Без этого пояснения ошибка выглядит как "кнопка ничего не делает".
            SignInResult.Failure(e.statusCode, describeError(e.statusCode))
        }
    }

    private fun describeError(statusCode: Int): String = when (statusCode) {
        10 -> "Вход не настроен (код 10, DEVELOPER_ERROR). В Google Cloud Console создай " +
            "OAuth-клиент типа «Android» с package name ${context.packageName} и SHA-1 " +
            "${signingSha1() ?: "(не удалось прочитать)"}. Этот SHA-1 — у установленной сборки; " +
            "если подпись у каждой сборки новая, сначала настрой стабильный ключ (README)."
        12500 -> "Google отклонил вход (код 12500). Обычно: на экране согласия OAuth не указан email " +
            "поддержки или проект в режиме «Тестирование», а твой аккаунт не добавлен в тестовые " +
            "пользователи. Ещё причина — устаревшие Google Play Services."
        12501 -> "Вход отменён."
        12502 -> "Вход уже выполняется — подожди пару секунд."
        7 -> "Нет соединения с интернетом."
        else -> "Не удалось войти (код $statusCode)."
    }

    fun signOut(onComplete: () -> Unit) {
        client.signOut().addOnCompleteListener {
            prefs.ownerEmail = null
            prefs.ownerName = null
            onComplete()
        }
    }
}

sealed class SignInResult {
    data class Success(val account: GoogleSignInAccount?) : SignInResult()
    data class Failure(val statusCode: Int, val message: String) : SignInResult()
}

/** Простое хранилище последнего вошедшего email/имени — не секрет, просто
 *  чтобы показать "Вы вошли как ..." даже до того, как Play Services
 *  синхронно вернут `lastSignedInAccount()` при холодном старте. Плюс список
 *  email-адресов с ролью Admin (см. AccountManager.Role). */
class AccountPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("voice_agent_account_prefs", Context.MODE_PRIVATE)

    var ownerEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value).apply()
        }

    /** «Продолжить без входа» — не показывать экран входа при каждом холодном старте. */
    var lockSkipped: Boolean
        get() = prefs.getBoolean(KEY_SKIPPED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SKIPPED, value).apply()
        }

    var ownerName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_NAME, value).apply()
        }

    fun isAdmin(email: String): Boolean = adminEmails().contains(email.lowercase())

    /** Вызывается при каждом успешном входе — если админов ещё вообще не было
     *  на этом телефоне, первый вошедший им и становится. */
    fun registerAsAdminIfFirstEver(email: String) {
        if (adminEmails().isEmpty()) addAdmin(email)
    }

    fun addAdmin(email: String) {
        val set = adminEmails().toMutableSet()
        set.add(email.lowercase())
        prefs.edit().putStringSet(KEY_ADMINS, set).apply()
    }

    fun adminEmails(): Set<String> = prefs.getStringSet(KEY_ADMINS, emptySet()) ?: emptySet()

    companion object {
        private const val KEY_EMAIL = "owner_email"
        private const val KEY_NAME = "owner_name"
        private const val KEY_ADMINS = "admin_emails"
        private const val KEY_SKIPPED = "lock_skipped"
    }
}
