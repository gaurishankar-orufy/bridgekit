package com.bridge.kit.core

import android.app.Activity
import java.lang.ref.WeakReference

/** The host's current Activity, for features that show dialogs, ads or SDK screens. Weak, so it never leaks. */
object ActivityHolder {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    val current: Activity? get() = ref?.get()

    fun set(activity: Activity) {
        ref = WeakReference(activity)
    }

    /** Clears only if [activity] is still the current one, so a recreated Activity is not wiped by the old one's onDestroy. */
    fun clear(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }
}
