package io.github.drtron1c.cloudphotos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drtron1c.cloudphotos.auth.CodeDelivery

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(p.groupedBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

@Composable
fun SplashScreen() {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(88.dp)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
    }
}

@Composable
fun LoginScreen(state: Phase.Login, vm: MainViewModel, onSignIn: (String, String, Boolean) -> Unit) {
    val p = LocalPalette.current
    var dnsDialog by remember { mutableStateOf(false) }
    var appleId by rememberSaveable { mutableStateOf(state.appleId) }
    var password by rememberSaveable { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var keep by rememberSaveable { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSignIn(appleId.trim(), password, keep)
    }

    CenteredColumn {
        AppIcon(88.dp)
        Spacer(Modifier.height(20.dp))
        Text("Cloud Photos", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = p.label)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with your Apple Account to see your iCloud Photos library.",
            color = p.secondaryLabel, textAlign = TextAlign.Center, fontSize = 16.sp,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = appleId,
            onValueChange = { appleId = it },
            label = { Text("Email or phone number") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remember password", color = p.label, fontSize = 16.sp)
                Text(
                    "Stored encrypted on this device, so you only need 2FA codes rarely.",
                    color = p.secondaryLabel, fontSize = 13.sp,
                )
            }
            Switch(checked = keep, onCheckedChange = { keep = it }, enabled = !state.busy)
        }
        if (state.error != null) {
            Spacer(Modifier.height(14.dp))
            Text(state.error, color = p.destructive, textAlign = TextAlign.Center, fontSize = 15.sp)
        }
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = { submit() },
            enabled = !state.busy && appleId.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
            else Text("Sign In", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = p.secondaryLabel, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                "Uses the same secure sign-in as icloud.com — your password itself is never sent.",
                color = p.secondaryLabel, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Unofficial app. Not made by or affiliated with Apple.",
            color = p.secondaryLabel, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        TextButton(onClick = { dnsDialog = true }) {
            Text("Network privacy: " + (DNS_OPTIONS.firstOrNull { it.first == vm.settings.dnsProvider }?.second ?: "Encrypted DNS"), fontSize = 13.sp)
        }
    }
    if (dnsDialog) DnsDialog(vm) { dnsDialog = false }
}

/** Lets someone on a network that blocks encrypted DNS still sign in. */
@Composable
fun DnsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    ChoiceDialog(
        title = "Encrypted DNS",
        options = DNS_OPTIONS,
        selected = vm.settings.dnsProvider,
        footer = "Looks up Apple's server addresses over HTTPS so your Wi-Fi or mobile provider can't log them. If this network blocks it, pick another provider.",
        onDismiss = onDismiss,
    ) { vm.setDns(it); onDismiss() }
}

@Composable
fun TwoFactorScreen(state: Phase.TwoFactor, vm: MainViewModel) {
    val p = LocalPalette.current
    val len = state.info.codeLength
    var code by remember(state.error) { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.error) { runCatching { focus.requestFocus() } }
    LaunchedEffect(state.busy) { if (!state.busy && state.error != null) code = "" }

    CenteredColumn {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { vm.cancelSignIn() }) { Text("Cancel") }
        }
        Spacer(Modifier.height(12.dp))
        Icon(Icons.Filled.Lock, null, tint = p.accent, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text("Two-Factor Authentication", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        val msg = when (val d = state.info.delivery) {
            is CodeDelivery.Devices -> "A sign-in request was sent to your iPhone, iPad or Mac. Tap Allow there, then enter the $len-digit code it shows."
            is CodeDelivery.Sms -> "Enter the $len-digit code sent to ${d.phone.number}."
        }
        Text(msg, color = p.secondaryLabel, textAlign = TextAlign.Center, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))

        BasicTextField(
            value = code,
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(len)
                code = digits
                if (digits.length == len) vm.submitCode(digits)
            },
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier.focusRequester(focus),
            decorationBox = { inner ->
                Box {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until len) {
                        val ch = code.getOrNull(i)?.toString() ?: ""
                        val active = i == code.length && !state.busy
                        Box(
                            Modifier
                                .size(width = 44.dp, height = 56.dp)
                                .background(p.card, RoundedCornerShape(10.dp))
                                .border(
                                    if (active) 2.dp else 1.dp,
                                    if (active) p.accent else p.separator,
                                    RoundedCornerShape(10.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(ch, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = p.label)
                        }
                    }
                }
                Box(Modifier.matchParentSize()) { inner() }
                }
            },
        )
        Spacer(Modifier.height(18.dp))
        when {
            state.busy -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
            state.error != null -> Text(state.error, color = p.destructive, textAlign = TextAlign.Center)
            state.notice != null -> Text(state.notice, color = p.secondaryLabel, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = { showOptions = true }, enabled = !state.busy) { Text("Didn't get a code?") }
    }

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("Get a new code") },
            text = {
                Column {
                    if (state.info.hasTrustedDevices) {
                        TextButton(onClick = { showOptions = false; vm.resendToDevices() }) {
                            Text("Send to my Apple devices again")
                        }
                    }
                    for (phone in state.info.phones) {
                        TextButton(onClick = { showOptions = false; vm.sendSms(phone) }) {
                            Text("Text a code to ${phone.number}")
                        }
                    }
                    if (!state.info.hasTrustedDevices && state.info.phones.isEmpty()) {
                        Text("Apple didn't offer any other way to send a code for this account.")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showOptions = false }) { Text("Close") } },
        )
    }
}

@Composable
fun WaitingScreen(message: String, onCancel: () -> Unit) {
    val p = LocalPalette.current
    CenteredColumn {
        AppIcon(72.dp)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.height(20.dp))
        Text(message, color = p.label, textAlign = TextAlign.Center, fontSize = 17.sp)
        Spacer(Modifier.height(28.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
fun FailedScreen(message: String, vm: MainViewModel, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val p = LocalPalette.current
    var dnsDialog by remember { mutableStateOf(false) }
    CenteredColumn {
        AppIcon(72.dp)
        Spacer(Modifier.height(24.dp))
        Text("Couldn't open your library", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(message, color = p.secondaryLabel, textAlign = TextAlign.Center, fontSize = 16.sp)
        Spacer(Modifier.height(26.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Try Again", fontSize = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { dnsDialog = true }) { Text("Network privacy settings") }
        TextButton(onClick = onSignOut, colors = ButtonDefaults.textButtonColors(contentColor = p.destructive)) {
            Text("Sign Out")
        }
    }
    if (dnsDialog) DnsDialog(vm) { dnsDialog = false }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, null, tint = p.secondaryLabel, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Cloud Photos is locked", color = p.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onUnlock, shape = RoundedCornerShape(14.dp)) { Text("Unlock") }
        }
    }
}
