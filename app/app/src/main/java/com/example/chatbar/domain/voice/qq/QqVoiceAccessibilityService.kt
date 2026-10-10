package com.example.chatbar.domain.voice.qq

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.example.chatbar.ChatBarApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QqVoiceAccessibilityService : AccessibilityService(), QqVoiceGestureDelegate {
    @Volatile
    private var serviceConnected = false
    @Volatile
    private var activeTarget: QqVoiceGestureTarget? = null
    private var gestureGeneration = 0L
    private var attachJob: Job? = null

    override val connected: Boolean
        get() = serviceConnected

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnected = true
        attachJob = ChatBarApp.instance.applicationScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ChatBarApp.instance.backupStartupReady.first { it }
            if (serviceConnected) ChatBarApp.instance.qqVoiceGestureGateway.attach(this@QqVoiceAccessibilityService)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        if (!ChatBarApp.instance.dependenciesReady.value) return
        ChatBarApp.instance.qqVoiceTransferCoordinator.fail(
            QqVoiceTransferPolicy.failure(
                QqVoiceTransferFailureCode.ACCESSIBILITY_DISABLED,
                "QQ 语音发送无障碍服务已中断"
            )
        )
    }

    override fun isQqForeground(): Boolean =
        rootInActiveWindow?.packageName?.toString() == QqVoiceTransferPolicy.QQ_PACKAGE

    override fun locateTarget(): QqVoiceGestureTarget? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != QqVoiceTransferPolicy.QQ_PACKAGE) return null
        val target = root.findAccessibilityNodeInfosByViewId(
            QqVoiceTransferPolicy.QQ_PRESS_TO_SPEAK_VIEW_ID
        ).firstOrNull { node ->
            node.isVisibleToUser && node.isEnabled
        } ?: return null
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null
        return QqVoiceGestureTarget(
            centerX = bounds.exactCenterX(),
            centerY = bounds.exactCenterY()
        )
    }

    override fun pressAndHold(
        target: QqVoiceGestureTarget,
        durationMs: Long,
        onCompletion: (QqVoiceGestureCompletion) -> Unit
    ): Boolean {
        if (!serviceConnected || !isQqForeground()) return false
        val generation = ++gestureGeneration
        activeTarget = target
        val path = Path().apply { moveTo(target.centerX, target.centerY) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceAtLeast(1L)
                )
            )
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (generation == gestureGeneration) activeTarget = null
                    onCompletion(QqVoiceGestureCompletion.COMPLETED)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (generation == gestureGeneration) activeTarget = null
                    onCompletion(QqVoiceGestureCompletion.CANCELLED)
                }
            },
            null
        )
        if (!accepted && generation == gestureGeneration) activeTarget = null
        return accepted
    }

    override fun cancelActiveGesture(): Boolean {
        val target = activeTarget ?: return false
        if (!serviceConnected || !isQqForeground()) {
            activeTarget = null
            gestureGeneration++
            return false
        }
        activeTarget = null
        gestureGeneration++
        val releasePath = Path().apply { moveTo(target.centerX, target.centerY) }
        val releaseGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(releasePath, 0L, 1L))
            .build()
        return dispatchGesture(releaseGesture, null, null)
    }

    override fun onDestroy() {
        cancelActiveGesture()
        serviceConnected = false
        attachJob?.cancel()
        if (ChatBarApp.instance.dependenciesReady.value) ChatBarApp.instance.qqVoiceGestureGateway.detach(this)
        super.onDestroy()
    }
}
