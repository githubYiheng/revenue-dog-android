package org.revdog.purchases

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 只关心 `onActivityStarted` 的 `Application.ActivityLifecycleCallbacks`。
 * 结构对照 RC `utils/CustomActivityLifecycleHandler.kt`（它同样把七个方法的空实现摊在一处，
 * 免得每个用得上它的地方都抄一遍）。
 *
 * 目前唯一的用途是 Play in-app message 的自动展示
 * （[PurchasesOrchestrator.onActivityStarted]）。七个方法一律 `= Unit` 而不是空花括号：
 * detekt 的 `EmptyFunctionBlock` 不允许空块，表达式体也更短。
 */
internal class ActivityStartedHandler(
    private val onStarted: (Activity) -> Unit,
) : Application.ActivityLifecycleCallbacks {

    override fun onActivityStarted(activity: Activity): Unit = onStarted(activity)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
