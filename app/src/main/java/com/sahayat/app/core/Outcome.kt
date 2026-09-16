package com.sahayat.app.core

/** Result of a one-shot operation (write, callable, export...). */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(block: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(block(value))
    is Outcome.Failure -> this
}

/**
 * A value observed from the backend or device. `Unavailable` is a first-class state:
 * the UI must render it honestly instead of substituting placeholder data.
 */
sealed interface Remote<out T> {
    data object Loading : Remote<Nothing>
    data class Unavailable(val error: AppError) : Remote<Nothing>
    data class Data<T>(val value: T) : Remote<T>
}

val <T> Remote<T>.valueOrNull: T? get() = (this as? Remote.Data<T>)?.value

/** Every failure the app knows how to explain to the user. */
sealed interface AppError {
    data object NoInternet : AppError
    data object BackendNotConfigured : AppError
    data object BackendUnavailable : AppError
    data object Timeout : AppError
    data object NotAuthenticated : AppError
    data object PermissionDenied : AppError
    data object NotFound : AppError
    data object NoPatient : AppError
    data object NoDevicePaired : AppError
    data object InvalidDeviceId : AppError
    data object InvalidPairingCode : AppError
    data object DeviceNotFound : AppError
    data object DeviceAlreadyLinked : AppError
    data object InvalidCredentials : AppError
    data object AccountExists : AppError
    data object WeakPassword : AppError
    data object FcmUnavailable : AppError
    data object NotificationPermissionDenied : AppError
    data object ExportFailed : AppError
    data object InvalidInput : AppError
    data object InvalidInvite : AppError
    data object RateLimited : AppError
    data object BluetoothUnavailable : AppError
    /** Firestore refused to link the band: it belongs to another account's patient, or the rules aren't deployed. */
    data object BandLinkDenied : AppError
    data class Unknown(val detail: String?) : AppError
}
