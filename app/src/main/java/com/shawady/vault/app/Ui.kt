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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.shawady.vault.core.BiometricUnlock
import com.shawady.vault.core.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PasswordField(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Shared unlock screen (used by the app and by the autofill auth screen).
 * Tries biometrics automatically when enabled; master password is always available.
 * Wrong password => exponential delay (1s, 2s, 4s ... max 30s).
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
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fails by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val bio = remember { BiometricUnlock(activity) }
    val blob = remember { store.loadBlob() }
    val bioOk = blob != null && bio.isAvailable() && store.biometricStillAllowed()

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
                fails++
                error = "كلمة المرور غير صحيحة"
                delay(minOf(30_000L, 1000L shl minOf(fails, 5)))
                busy = false
            } else {
                store.markMasterUnlock()
                pw = ""
                busy = false
                onUnlocked(result.first, result.second)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoMark(72.dp)
        Text("K2Pass", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("افتح الخزنة", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PasswordField("الماستر باسورد", pw, !busy) { pw = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { submit() }, enabled = !busy && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "جاري الفتح..." else "فتح")
        }
        if (bioOk) {
            OutlinedButton(onClick = { tryBio() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("بصمة / وجه")
            }
        }
        extra()
    }
}
