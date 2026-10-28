package ai.deepseek.dsh.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.deepseek.dsh.android.navigation.DshApp

/**
 * 组合根的启动 Activity。
 *
 * 边到边：`enableEdgeToEdge()` 必须在 `setContent` 之前；ComponentActivity 的这个入口会自动处理
 * 系统栏图标明暗，主题侧只需 NoActionBar + 透明系统栏（见 res/values(-night)/themes.xml）。
 * 底部有 NavigationBar，因此 API 29+ 关闭导航栏对比遮罩，让底栏颜色铺到系统栏下。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val appScope = (application as DshApplication).appScope
        setContent {
            DshApp(appScope)
        }
    }
}
