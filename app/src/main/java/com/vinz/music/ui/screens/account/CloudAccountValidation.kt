package com.vinz.music.ui.screens.account

import java.util.Locale

/** Mirrors only the existing client's input constraints. Server validation remains authoritative. */
internal object CloudAccountValidation {
    fun username(value: String): String = value.lowercase(Locale.ROOT)
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(24)

    fun otp(value: String): String = value.filter { it in '0'..'9' }.take(6)

    fun validEmail(value: String): Boolean {
        val email = value.trim()
        val at = email.indexOf('@')
        return email.length <= 254 && at > 0 && at == email.lastIndexOf('@') &&
            at < email.lastIndex && email.none { it.isWhitespace() }
    }

    fun validProfile(draft: CloudProfileDraft): Boolean =
        draft.username.matches(Regex("^[a-z0-9_]{3,24}$")) &&
            draft.displayName.trim().length in 1..60 && draft.bio.length <= 160

    fun canSubmit(state: CloudAccountState): Boolean = when (state.mode) {
        CloudAccountMode.VERIFY_EMAIL -> validEmail(state.email) && state.otp.matches(Regex("^[0-9]{6}$"))
        CloudAccountMode.RECOVERY -> validEmail(state.email)
        CloudAccountMode.SIGN_IN -> validEmail(state.email) && state.password.isNotEmpty()
        CloudAccountMode.SIGN_UP -> state.identity == null && validEmail(state.email) && state.password.length >= 8 &&
            validProfile(state.draft) && state.usernameAvailability == UsernameAvailability.AVAILABLE
        CloudAccountMode.RESET_PASSWORD -> state.identity != null && state.password.length >= 8 &&
            state.confirmPassword == state.password
    }
}
