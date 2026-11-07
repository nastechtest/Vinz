package com.vinz.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/**
 * Vinz Account is separate from the optional YouTube Music session. Local playback and library use do not depend on it.
 * This screen handles Supabase email identity and the cloud-backed public/private profile.
 */
@Composable
fun VinzCloudAccountScreen(onBack: () -> Unit) {
    val cloud = LocalContainer.current.vinzCloudAccount
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(cloud.session) }
    var profile by remember { mutableStateOf<VinzCloudAccount.Profile?>(null) }
    var registering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(session?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(true) }
    var discoverable by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf(false) }

    LaunchedEffect(session?.userId) {
        if (session != null) {
            busy = true
            runCatching { cloud.loadProfile() }
                .onSuccess { loaded ->
                    profile = loaded
                    if (loaded != null) {
                        username = loaded.username
                        displayName = loaded.displayName
                        bio = loaded.bio
                        isPublic = loaded.isPublic
                        discoverable = loaded.discoverable
                    }
                }
                .onFailure { error = it.message ?: "Couldn't load your profile." }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp).widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            VinzTextButton(onClick = onBack) { Text("Back") }
            if (session != null) VinzTextButton(onClick = {
                scope.launch {
                    busy = true
                    cloud.signOut()
                    session = null
                    profile = null
                    password = ""
                    message = "You've signed out of your Vinz Account."
                    busy = false
                }
            }) { Text("Sign out") }
        }
        Text(if (session == null) "Your Vinz Account" else "Your Vinz profile", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (session == null) "Sign in or create an account to sync your profile and use Vinz cloud features. Your music stays available on this device without signing in."
            else "Your Vinz identity is separate from YouTube Music. Your local music and playback remain available if you sign out.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (busy) CircularProgressIndicator()
        if (session == null) {
            if (recovery) {
                OutlinedTextField(email, { email = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Email address") }, singleLine = true)
                VinzButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; error = null; message = null
                        runCatching { cloud.sendPasswordRecovery(email) }
                            .onSuccess { message = "If an account exists for this address, a password recovery email has been sent." }
                            .onFailure { error = it.message ?: "Couldn't send the recovery email." }
                        busy = false
                    }
                }) { Text("Send recovery email") }
                VinzTextButton(onClick = { recovery = false }) { Text("Back to sign in") }
            } else {
                if (registering) {
                    OutlinedTextField(username, { username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(24); error = null }, Modifier.fillMaxWidth(), label = { Text("Username") }, supportingText = { Text("3–24 characters · lowercase letters, numbers and _") }, singleLine = true)
                    OutlinedTextField(displayName, { displayName = it.take(60); error = null }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
                }
                OutlinedTextField(email, { email = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Email address") }, singleLine = true)
                OutlinedTextField(password, { password = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (registering) Text("Use at least 8 characters for your password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VinzButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; error = null; message = null
                        runCatching {
                            if (registering) cloud.signUp(email, password, username, displayName)
                            else cloud.signIn(email, password)
                        }.onSuccess { result ->
                            when (result) {
                                is VinzCloudAccount.AuthResult.SignedIn -> {
                                    session = cloud.session
                                    profile = result.profile
                                    message = if (result.profile == null) "Finish setting up your profile." else "Welcome back to Vinz."
                                }
                                VinzCloudAccount.AuthResult.EmailConfirmationRequired ->
                                    message = "Check your email to confirm your Vinz Account, then come back to sign in."
                            }
                        }.onFailure { error = it.message ?: "Couldn't complete sign-in." }
                        busy = false
                    }
                }) { Text(if (registering) "Create Vinz Account" else "Sign in") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    VinzTextButton(onClick = { registering = !registering; error = null; message = null }) {
                        Text(if (registering) "I already have an account" else "Create an account")
                    }
                    if (!registering) VinzTextButton(onClick = { recovery = true; error = null; message = null }) { Text("Forgot password?") }
                }
            }
        } else {
            OutlinedTextField(username, { username = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(24); error = null }, Modifier.fillMaxWidth(), label = { Text("Username") }, supportingText = { Text("3–24 characters · lowercase letters, numbers and _") }, singleLine = true)
            OutlinedTextField(displayName, { displayName = it.take(60); error = null }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
            OutlinedTextField(bio, { bio = it.take(160); error = null }, Modifier.fillMaxWidth(), label = { Text("Bio") }, supportingText = { Text("${bio.length}/160") }, minLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Public profile", style = MaterialTheme.typography.titleSmall)
                    Text("Allow other Vinz users to view your profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(isPublic, { isPublic = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Discoverable", style = MaterialTheme.typography.titleSmall)
                    Text("Allow your profile to appear in user discovery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(discoverable, { discoverable = it })
            }
            VinzButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; error = null; message = null
                    runCatching { cloud.saveProfile(username, displayName, bio, isPublic, discoverable) }
                        .onSuccess { profile = it; message = "Your Vinz profile is saved." }
                        .onFailure { error = it.message ?: "Couldn't save your profile." }
                    busy = false
                }
            }) { Text("Save profile") }
            Text("Signed in as ${session?.email.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(18.dp))
    }
}
