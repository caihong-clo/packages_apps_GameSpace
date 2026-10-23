/*
 * SPDX-FileCopyrightText: 2026 PenguinOS
 * SPDX-License-Identifier: Apache-2.0
 *
 * Touch node ids and enable values follow the LineageOS hardware/oplus touch HAL
 * (OplusTouchConstants, HighTouchPollingRate) by Maitreya25, inferno0230, Mashopy,
 * Bruno Martins and kmiit.
 */
package io.chaldeaprjkt.gamespace.data

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.util.BoostFramework
import android.util.Log
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OPlus touch firmware and display tuning applied while a game is in the foreground.
 *
 * Only active when the device overlay sets config_supportOplusGameTuning (OnePlus Pad 2).
 * Talks to vendor.oplus.hardware.touch.IOplusTouch with plain binder transactions so that
 * GameSpace keeps building on trees without the OPlus interface libraries. Every node is
 * checked with isTouchNodeSupport before it is offered or written.
 *
 * Games in Performance mode also get the QTI perf HAL "performance mode" hint
 * (VENDOR_HINT_PERFORMANCE_MODE, tuned per target in perfboostsconfig.xml).
 */
@Singleton
class DeviceTuning @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var boostFramework: BoostFramework? = null
    private var perfHandle = -1
    private var perfRenewal: ScheduledFuture<*>? = null
    private var touchBinder: IBinder? = null
    private val nodeSupport = HashMap<Int, Boolean>()

    val isEnabled: Boolean
        get() = context.resources.getBoolean(R.bool.config_supportOplusGameTuning)

    // Some touch stacks parse node 26 as a hex report rate rather than a boolean.
    private val gameModeValue: String
        get() = context.getString(R.string.config_oplusTouchGameModeValue)

    /** Blocking; call off the main thread. */
    fun isNodeSupported(node: Int): Boolean {
        if (!isEnabled) return false
        synchronized(nodeSupport) {
            nodeSupport[node]?.let { return it }
        }
        val supported = transact(TX_IS_NODE_SUPPORT, node, null)?.let { it > 0 } ?: false
        synchronized(nodeSupport) { nodeSupport[node] = supported }
        return supported
    }

    fun applyForGame() {
        if (!isEnabled) return
        val gameMode = prefs.getBoolean(KEY_TOUCH_GAME_MODE, true)
        val sensitivity = prefs.getString(KEY_TOUCH_SENSITIVITY, "0")?.toIntOrNull() ?: 0
        val smoothness = prefs.getString(KEY_TOUCH_SMOOTHNESS, "0")?.toIntOrNull() ?: 0
        val refreshRate = prefs.getString(KEY_REFRESH_RATE, "0")?.toFloatOrNull() ?: 0f
        executor.execute {
            if (gameMode) writeNode(NODE_GAME_SWITCH, gameModeValue)
            if (sensitivity in LEVEL_RANGE) writeNode(NODE_SENSITIVITY, sensitivity.toString())
            if (smoothness in LEVEL_RANGE) writeNode(NODE_SMOOTHNESS, smoothness.toString())
        }
        if (refreshRate > 0f) lockRefreshRate(refreshRate)
    }

    fun restore() {
        if (!isEnabled) return
        executor.execute {
            // 0 hands control back to the firmware defaults, like the stock game service.
            writeNode(NODE_GAME_SWITCH, "0")
            writeNode(NODE_SENSITIVITY, "0")
            writeNode(NODE_SMOOTHNESS, "0")
        }
        unlockRefreshRate()
    }

    /** Holds the perf HAL performance mode while [enable] is true; safe to call repeatedly. */
    fun setPerformanceBoost(enable: Boolean) {
        if (!isEnabled) return
        val boost = enable && prefs.getBoolean(KEY_PERF_BOOST, true)
        executor.execute {
            if (boost) {
                if (perfRenewal == null) {
                    // The hint is taken for a bounded time and renewed, so it lapses on its own
                    // if GameSpace dies without releasing it.
                    perfRenewal = executor.scheduleWithFixedDelay(::acquirePerfHint, 0,
                        PERF_RENEW_MS, TimeUnit.MILLISECONDS)
                }
            } else {
                perfRenewal?.cancel(false)
                perfRenewal = null
                releasePerfHint()
            }
        }
    }

    private fun acquirePerfHint() {
        val framework = boostFramework ?: BoostFramework(context).also { boostFramework = it }
        val previous = perfHandle
        perfHandle = framework.perfHint(BoostFramework.VENDOR_HINT_PERFORMANCE_MODE,
            context.packageName, PERF_HINT_MS, -1)
        if (perfHandle < 0) Log.w(TAG, "performance mode hint failed: $perfHandle")
        if (previous >= 0 && previous != perfHandle) framework.perfLockReleaseHandler(previous)
    }

    private fun releasePerfHint() {
        if (perfHandle < 0) return
        boostFramework?.perfLockReleaseHandler(perfHandle)
        perfHandle = -1
    }

    private fun lockRefreshRate(rate: Float) {
        val resolver = context.contentResolver
        if (!prefs.getBoolean(KEY_SAVED_REFRESH, false)) {
            prefs.edit()
                .putBoolean(KEY_SAVED_REFRESH, true)
                .putString(KEY_SAVED_MIN_REFRESH, Settings.System.getStringForUser(
                    resolver, MIN_REFRESH_RATE, UserHandle.USER_CURRENT))
                .putString(KEY_SAVED_PEAK_REFRESH, Settings.System.getStringForUser(
                    resolver, PEAK_REFRESH_RATE, UserHandle.USER_CURRENT))
                .commit()
        }
        val value = rate.toString()
        Settings.System.putStringForUser(resolver, PEAK_REFRESH_RATE, value, UserHandle.USER_CURRENT)
        Settings.System.putStringForUser(resolver, MIN_REFRESH_RATE, value, UserHandle.USER_CURRENT)
    }

    private fun unlockRefreshRate() {
        if (!prefs.getBoolean(KEY_SAVED_REFRESH, false)) return
        val resolver = context.contentResolver
        Settings.System.putStringForUser(resolver, MIN_REFRESH_RATE,
            prefs.getString(KEY_SAVED_MIN_REFRESH, null), UserHandle.USER_CURRENT)
        Settings.System.putStringForUser(resolver, PEAK_REFRESH_RATE,
            prefs.getString(KEY_SAVED_PEAK_REFRESH, null), UserHandle.USER_CURRENT)
        prefs.edit()
            .remove(KEY_SAVED_REFRESH)
            .remove(KEY_SAVED_MIN_REFRESH)
            .remove(KEY_SAVED_PEAK_REFRESH)
            .commit()
    }

    private fun writeNode(node: Int, value: String) {
        if (!isNodeSupported(node)) return
        val ret = transact(TX_WRITE_NODE, node, value)
        if (ret == null || ret < 0) Log.w(TAG, "write node $node=$value failed: $ret")
    }

    @Synchronized
    private fun service(): IBinder? {
        touchBinder?.takeIf { it.isBinderAlive }?.let { return it }
        touchBinder = if (ServiceManager.isDeclared(TOUCH_SERVICE)) {
            ServiceManager.waitForDeclaredService(TOUCH_SERVICE)
        } else {
            null
        }
        return touchBinder
    }

    /** Mirrors the generated IOplusTouch proxy for the int-returning node calls. */
    private fun transact(code: Int, node: Int, value: String?): Int? {
        val binder = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOUCH_DESCRIPTOR)
            data.writeInt(DEFAULT_TP_IC_ID)
            data.writeInt(node)
            if (value != null) data.writeString(value)
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: RemoteException) {
            Log.e(TAG, "touch HAL call $code failed", e)
            touchBinder = null
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "touch HAL call $code failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val TAG = "GameSpace:DeviceTuning"

        private const val TOUCH_DESCRIPTOR = "vendor.oplus.hardware.touch.IOplusTouch"
        private const val TOUCH_SERVICE = "$TOUCH_DESCRIPTOR/default"
        private const val TX_IS_NODE_SUPPORT = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TX_WRITE_NODE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val DEFAULT_TP_IC_ID = 0

        // Node ids of the OPlus touch HAL (see OplusTouchConstants / COSA).
        const val NODE_SMOOTHNESS = 24
        const val NODE_SENSITIVITY = 25
        const val NODE_GAME_SWITCH = 26
        private val LEVEL_RANGE = 1..5

        private const val PERF_HINT_MS = 5 * 60 * 1000
        private const val PERF_RENEW_MS = 4 * 60 * 1000L

        private const val MIN_REFRESH_RATE = "min_refresh_rate"
        private const val PEAK_REFRESH_RATE = "peak_refresh_rate"

        const val KEY_CATEGORY = "gamespace_device_tuning"
        const val KEY_TOUCH_GAME_MODE = "gamespace_touch_game_mode"
        const val KEY_TOUCH_SENSITIVITY = "gamespace_touch_sensitivity"
        const val KEY_TOUCH_SMOOTHNESS = "gamespace_touch_smoothness"
        const val KEY_REFRESH_RATE = "gamespace_refresh_rate_lock"
        const val KEY_PERF_BOOST = "gamespace_perf_mode_boost"
        private const val KEY_SAVED_REFRESH = "gamespace_refresh_saved"
        private const val KEY_SAVED_MIN_REFRESH = "gamespace_refresh_saved_min"
        private const val KEY_SAVED_PEAK_REFRESH = "gamespace_refresh_saved_peak"
    }
}
