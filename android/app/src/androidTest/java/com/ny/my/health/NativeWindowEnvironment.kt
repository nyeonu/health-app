package com.ny.my.health

import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** 합성 CI에서 관측된 외부 ANR만 닫으며 앱·권한·계정 대화상자는 건드리지 않는다. */
internal object NativeWindowEnvironment {
    private enum class Outcome { SYSTEM_UI_CLOSED, PIXEL_LAUNCHER_CLOSED, CLOSE_APP_NOT_PERFORMED }

    fun dismissKnownExternalAnr() {
        check(Looper.myLooper() != Looper.getMainLooper())
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != "android") return
            val outcome = when {
                containsExactText(root, "System UI isn't responding") -> Outcome.SYSTEM_UI_CLOSED
                containsExactText(root, "Pixel Launcher isn't responding") -> Outcome.PIXEL_LAUNCHER_CLOSED
                else -> return
            }
            // 승인된 루트 안의 정확한 닫기 버튼에만 공개 접근성 클릭을 수행한다.
            val closed = clickExactCloseApp(root)
            Log.i("NativeWindowEnvironment", (if (closed) outcome else Outcome.CLOSE_APP_NOT_PERFORMED).name)
        } finally {
            root.recycle()
        }
    }

    private fun containsExactText(node: AccessibilityNodeInfo, expected: String): Boolean {
        if (node.packageName?.toString() != "android") return false
        if (node.text?.toString() == expected) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (containsExactText(child, expected)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun clickExactCloseApp(node: AccessibilityNodeInfo): Boolean {
        if (node.packageName?.toString() != "android") return false
        if (node.text?.toString() == "Close app" && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (clickExactCloseApp(child)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }
}
