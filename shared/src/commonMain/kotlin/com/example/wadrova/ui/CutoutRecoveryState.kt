package com.example.wadrova.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wadrova.data.CutoutRecovery
import com.example.wadrova.data.CutoutRecoveryEligibility
import com.example.wadrova.data.CutoutRecoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What Garment Details shows about recovering a garment's transparent cutout. */
data class CutoutRecoveryUi(
    val available: Boolean = false,
    val working: Boolean = false,
    val message: String? = null,
    val canRetry: Boolean = false,
)

/**
 * Offers "Restore transparent cutout" for an imported garment that has no cutout yet. Checking is local; only the
 * user's tap downloads anything, once per tap. A failure that can't change (the server doesn't have the cutout, or it
 * can't be used) is remembered for this session, so the same unavailable asset isn't requested again.
 */
class CutoutRecoveryViewModel(
    private val recovery: CutoutRecovery,
    private val onRecovered: () -> Unit,
) : ViewModel() {
    var state by mutableStateOf<Map<String, CutoutRecoveryUi>>(emptyMap())
        private set

    private val permanentFailures = mutableMapOf<String, String>()

    /** Local only: whether the garment can be offered recovery. Never contacts the server. */
    fun check(garmentId: String) {
        if (state[garmentId]?.working == true) return
        permanentFailures[garmentId]?.let { message ->
            state = state + (garmentId to CutoutRecoveryUi(message = message))
            return
        }
        viewModelScope.launch {
            val eligible = try {
                recovery.eligibility(garmentId) == CutoutRecoveryEligibility.ELIGIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            val current = state[garmentId]
            if (current?.working == true) return@launch
            val retrying = current?.canRetry == true
            val next = when {
                eligible -> CutoutRecoveryUi(available = true, message = current?.message?.takeIf { retrying }, canRetry = retrying)
                // A success message stays once the garment has its cutout.
                current?.message == RECOVERED -> CutoutRecoveryUi(message = RECOVERED)
                else -> CutoutRecoveryUi()
            }
            state = state + (garmentId to next)
        }
    }

    fun recover(garmentId: String) {
        val current = state[garmentId] ?: return
        if (current.working || !current.available) return
        state = state + (garmentId to current.copy(working = true, message = null))
        viewModelScope.launch {
            val result = try {
                recovery.recover(garmentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CutoutRecoveryResult.UNREACHABLE
            }
            val next = when (result) {
                CutoutRecoveryResult.RECOVERED -> CutoutRecoveryUi(message = RECOVERED)
                CutoutRecoveryResult.NOT_NEEDED -> CutoutRecoveryUi()
                CutoutRecoveryResult.NOT_CONNECTED -> retry("Connect to Wadrova to restore this cutout, then try again.")
                CutoutRecoveryResult.UNREACHABLE -> retry("Couldn't reach Wadrova or verify the cutout. Try again later.")
                CutoutRecoveryResult.STORAGE_FAILED -> retry("Couldn't store the cutout on this device. Try again later.")
                CutoutRecoveryResult.CHANGED -> CutoutRecoveryUi(message = "This piece's photo changed, so its cutout wasn't restored.")
                CutoutRecoveryResult.NOT_ON_SERVER -> permanent(garmentId, "Wadrova no longer has this piece's cutout for this account.")
                CutoutRecoveryResult.UNUSABLE -> permanent(garmentId, "This piece's cutout couldn't be used.")
            }
            state = state + (garmentId to next)
            if (result == CutoutRecoveryResult.RECOVERED || result == CutoutRecoveryResult.NOT_NEEDED) onRecovered()
        }
    }

    private fun retry(message: String) = CutoutRecoveryUi(available = true, message = message, canRetry = true)

    private fun permanent(garmentId: String, message: String): CutoutRecoveryUi {
        permanentFailures[garmentId] = message
        return CutoutRecoveryUi(message = message)
    }

    companion object {
        const val RECOVERED = "Transparent cutout restored."
    }
}
