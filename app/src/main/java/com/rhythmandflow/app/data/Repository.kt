package com.rhythmandflow.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import retrofit2.HttpException
import retrofit2.Response

/** Result of a network call with a message that is safe to show to the user. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Fail(val message: String, val code: Int? = null) : Outcome<Nothing>()
}

inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Fail -> this
}

class Repository(private val api: Api, private val tokens: TokenStore) {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when the server rejects our token (expired/invalid) so the app can sign the user out. */
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    val hasToken get() = tokens.token != null

    private suspend fun <T> call(block: suspend () -> T): Outcome<T> = try {
        Outcome.Ok(block())
    } catch (e: HttpException) {
        val code = e.code()
        val msg = errorMessage(e.response()?.errorBody()?.string(), code)
        if (code == 401 && hasToken) _unauthorized.tryEmit(Unit)
        Outcome.Fail(msg, code)
    } catch (e: IOException) {
        Outcome.Fail("Can't reach the Rhythm & Flow server. Check your internet connection and try again.")
    } catch (e: Exception) {
        Outcome.Fail("Something went wrong. Please try again.")
    }

    private suspend fun callUnit(block: suspend () -> Response<Unit>): Outcome<Unit> = try {
        val r = block()
        if (r.isSuccessful) Outcome.Ok(Unit) else {
            if (r.code() == 401 && hasToken) _unauthorized.tryEmit(Unit)
            Outcome.Fail(errorMessage(r.errorBody()?.string(), r.code()), r.code())
        }
    } catch (e: IOException) {
        Outcome.Fail("Can't reach the Rhythm & Flow server. Check your internet connection and try again.")
    } catch (e: Exception) {
        Outcome.Fail("Something went wrong. Please try again.")
    }

    private fun errorMessage(body: String?, code: Int): String {
        if (!body.isNullOrBlank()) {
            try {
                val obj = JsonParser.parseString(body).asJsonObject
                obj.get("error")?.takeIf { !it.isJsonNull }?.asString?.let { return it }
                // ASP.NET validation problem details: {"errors":{"Password":["..."]}}
                obj.getAsJsonObject("errors")?.entrySet()?.firstOrNull()?.value?.asJsonArray?.firstOrNull()?.asString?.let { return it }
            } catch (_: Exception) { }
        }
        return when (code) {
            401 -> "Please sign in again."
            403 -> "You don't have access to that."
            404 -> "We couldn't find that."
            429 -> "Too many attempts. Please wait a moment."
            else -> "Something went wrong (error $code)."
        }
    }

    // ---- Auth ----
    suspend fun login(identifier: String, password: String) = call { api.login(LoginRequest(identifier, password)) }
        .also { if (it is Outcome.Ok) tokens.token = it.value.token }
    suspend fun register(name: String, username: String, email: String, password: String) =
        call { api.register(RegisterRequest(name, username, email, password)) }
            .also { if (it is Outcome.Ok) tokens.token = it.value.token }
    suspend fun forgotPassword(email: String) = call { api.forgotPassword(ForgotPasswordRequest(email.trim())) }
    suspend fun resetPassword(email: String, code: String, newPassword: String) =
        call { api.resetPassword(ResetPasswordRequest(email.trim(), code.trim(), newPassword)) }
    /** Changing the password signs other devices out, so the server returns a fresh token for this one. */
    suspend fun changePassword(current: String, new: String) = call { api.changePassword(ChangePasswordRequest(current, new)) }
        .also { if (it is Outcome.Ok) tokens.token = it.value.token }
    suspend fun me() = call { api.me() }
    suspend fun updateProfile(name: String, email: String, about: String?) = call { api.updateProfile(UpdateProfileRequest(name, email, about)) }
    fun signOut() = tokens.clear()

    // ---- Plans & subscriptions ----
    suspend fun plans() = call { api.plans() }
    suspend fun subscriptions() = call { api.subscriptions() }
    suspend fun checkout(planId: Int) = call { api.checkout(CheckoutRequest(planId)) }
    suspend fun cancelSubscription(id: Int) = call { api.cancelSubscription(id) }
    suspend fun simulatePayment(id: Int) = callUnit { api.simulatePayment(id) }

    // ---- Content ----
    suspend fun lessons(category: String? = null, query: String? = null) =
        call { api.lessons(category?.takeIf { it != "All" }, query?.takeIf { it.isNotBlank() }) }
    suspend fun lesson(id: Int) = call { api.lesson(id) }
    suspend fun playback(id: Int) = call { api.playback(id) }
    suspend fun updateProgress(lessonId: Int, seconds: Int) = call { api.updateProgress(ProgressUpdate(lessonId, seconds)) }
    suspend fun progressSummary() = call { api.progressSummary() }

    // ---- Classes ----
    suspend fun classes() = call { api.classes() }
    suspend fun bookings() = call { api.bookings() }
    suspend fun book(classId: Int) = call { api.book(classId) }
    suspend fun cancelBooking(id: Int) = callUnit { api.cancelBooking(id) }

    // ---- Journal ----
    suspend fun journal() = call { api.journal() }
    suspend fun addJournal(kind: String, mood: String?, text: String?) = call { api.addJournal(JournalRequest(kind, mood, text)) }
    suspend fun deleteJournal(id: Int) = callUnit { api.deleteJournal(id) }

    // ---- Admin ----
    suspend fun adminSummary() = call { api.adminSummary() }
    suspend fun adminClasses() = call { api.adminClasses() }
    suspend fun adminCreateClass(c: ClassUpsert) = call { api.adminCreateClass(c) }
    suspend fun adminCancelClass(id: Int) = callUnit { api.adminCancelClass(id) }
    suspend fun adminCreateLesson(l: LessonUpsert) = call { api.adminCreateLesson(l) }
    suspend fun adminDeleteLesson(id: Int) = callUnit { api.adminDeleteLesson(id) }
    suspend fun adminPlans() = call { api.adminPlans() }
    suspend fun adminUpdatePlan(id: Int, p: PlanUpsert) = callUnit { api.adminUpdatePlan(id, p) }

    @Suppress("unused") private val gson = Gson()
}
