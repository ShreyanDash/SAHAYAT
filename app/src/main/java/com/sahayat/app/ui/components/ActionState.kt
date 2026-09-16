package com.sahayat.app.ui.components

import androidx.compose.runtime.Composable
import com.sahayat.app.core.AppError
import com.sahayat.app.ui.i18n.LocalStrings

/** State of a one-shot backend action (reconnect, save, re-register...). */
sealed interface ActionState {
    data object Idle : ActionState
    data object Busy : ActionState
    data object Done : ActionState
    data class Failed(val error: AppError) : ActionState
}

/** Shows success only after the backend confirmed it, and the real error otherwise. */
@Composable
fun ActionStateBanner(state: ActionState, doneText: String, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    when (state) {
        ActionState.Done -> Banner(doneText, Tone.Ok, action = s.close to onDismiss)
        is ActionState.Failed -> Banner(s.error(state.error), Tone.Danger, action = s.close to onDismiss)
        else -> Unit
    }
}
