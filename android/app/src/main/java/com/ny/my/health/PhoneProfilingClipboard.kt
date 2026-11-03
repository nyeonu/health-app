package com.ny.my.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** 명시적 복사에서 받은 안전한 요약만 쓴다. 실패는 호출자가 처리한다. */
internal fun writePhoneProfilingClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("수동 동기화 성능 진단", text))
}
