package com.shawady.vault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.shawady.vault.core.BiometricUnlock
import com.shawady.vault.core.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One soft, rounded text-field look for the whole app (no hard outlines until focused). */
@Composable
fun k2FieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
)

/** Text field for secrets with an eye button that shows / hides the value. */
@Composable
fun SecretField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    show: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Password,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        colors = k2FieldColors(),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (show) EyeIcons.EyeOff else EyeIcons.Eye,
                    contentDescription = if (show) "إخفاء" else "إظهار",
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
fun PasswordField(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    SecretField(label, value, onChange, show, { show = !show }, enabled)
}

/**
 * Shared unlock screen (used by the app and by the autofill auth screen).
 * Tries biometrics automatically when enabled; PIN (if set up) or the master password are alternatives.
 * Wrong master password => exponential delay (1s, 2s, 4s ... max 30s). Wrong PIN => 5 tries then PIN is removed.
 * On success the caller owns the returned key and must wipe it.
 */
@Composable
fun UnlockScreen(
    activity: FragmentActivity,
    store: VaultStore,
    onUnlocked: (VaultCrypto.VaultFile, ByteArray) -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    var pw by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val limiter = remember { AttemptLimiter(activity.applicationContext) }
    val scope = rememberCoroutineScope()
    val bio = remember { BiometricUnlock(activity) }
    val blob = remember { store.loadBlob() }
    val bioOk = blob != null && bio.isAvailable() && store.biometricStillAllowed()
    val pinStore = remember { PinStore(activity.applicationContext) }
    var pinOk by remember { mutableStateOf(pinStore.isSet() && store.biometricStillAllowed()) }
    var usePin by remember { mutableStateOf(pinOk) }

    fun tryBio() {
        if (!bioOk || blob == null) return
        bio.unlock(
            blob,
            onKey = { k ->
                try {
                    onUnlocked(store.load(), k)
                } catch (e: Exception) {
                    k.fill(0)
                    error = "تعذر فتح الخزنة"
                }
            },
            onError = { msg -> error = msg },
        )
    }

    LaunchedEffect(Unit) { tryBio() }

    fun submit() {
        if (busy) return
        val wait = limiter.secondsLeft()
        if (wait > 0) { error = "محاولات كتير غلط. استنى $wait ثانية."; return }
        busy = true
        error = null
        val chars = pw.toCharArray()
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val f = store.load()
                    Pair(f, VaultCrypto.unlock(f, chars))
                } catch (e: Exception) {
                    null
                } finally {
                    chars.fill('\u0000')
                }
            }
            if (result == null) {
                limiter.fail()
                val w = limiter.secondsLeft()
                error = if (w > 0) "كلمة المرور غير صحيحة. استنى $w ثانية قبل المحاولة الجاية." else "كلمة المرور غير صحيحة"
                busy = false
            } else {
                limiter.reset()
                store.markMasterUnlock()
                pw = ""
                busy = false
                onUnlocked(result.first, result.second)
            }
        }
    }

    fun submitPin() {
        if (busy) return
        busy = true
        error = null
        val chars = pin.toCharArray()
        scope.launch {
            val key = withContext(Dispatchers.Default) {
                try {
                    pinStore.unlock(chars)
                } finally {
                    chars.fill('\u0000')
                }
            }
            busy = false
            pin = ""
            if (key == null) {
                if (!pinStore.isSet()) {
                    pinOk = false
                    usePin = false
                    error = "اتمسح الـ PIN بعد محاولات غلط. استخدم الماستر باسورد."
                } else {
                    error = "PIN غلط (باقي ${pinStore.attemptsLeft()} محاولات)"
                }
            } else {
                try {
                    onUnlocked(store.load(), key)
                } catch (e: Exception) {
                    key.fill(0)
                    error = "تعذر فتح الخزنة"
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoMark(88.dp)
        Text("K2Pass", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("افتح الخزنة", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (usePin) {
            PasswordField("PIN", pin, !busy) { v -> pin = v.filter { it.isDigit() } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { submitPin() }, enabled = !busy && pin.length >= PinStore.MIN_LEN,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "جاري الفتح..." else "فتح بالـ PIN") }
            TextButton(onClick = { usePin = false; error = null }) { Text("استخدم الماستر باسورد") }
        } else {
            PasswordField("الماستر باسورد", pw, !busy) { pw = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { submit() }, enabled = !busy && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "جاري الفتح..." else "فتح")
            }
            if (pinOk) {
                TextButton(onClick = { usePin = true; error = null }) { Text("استخدم PIN") }
            }
        }
        if (bioOk) {
            OutlinedButton(onClick = { tryBio() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("بصمة / وجه")
            }
        }
        extra()
    }
}


/** Persistent (survives app restarts) back-off for wrong master passwords: free 3 tries, then 10s doubling up to 15 min. */
class AttemptLimiter(ctx: android.content.Context) {
    private val p = ctx.getSharedPreferences("k2_attempts", android.content.Context.MODE_PRIVATE)
    fun secondsLeft(): Long = ((p.getLong("until", 0L) - System.currentTimeMillis()) / 1000L + 1).coerceAtLeast(0)
        .let { if (p.getLong("until", 0L) <= System.currentTimeMillis()) 0 else it }
    fun fail() {
        val n = p.getInt("fails", 0) + 1
        val delayMs = if (n < 3) 0L else minOf(15 * 60_000L, 10_000L shl minOf(n - 3, 7))
        p.edit().putInt("fails", n).putLong("until", System.currentTimeMillis() + delayMs).apply()
    }
    fun reset() { p.edit().clear().apply() }
}
