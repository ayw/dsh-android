package ai.deepseek.dsh.android.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 给承载凭据相关信息的屏幕加 FLAG_SECURE：禁止截屏与最近任务缩略图。
 *
 * 作为可选包装层提供，配对等后续屏幕可复用；预览环境不触碰窗口。
 */
@Composable
fun SecureScreen(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view) {
            val window = view.context.findActivity()?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
    content()
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}
