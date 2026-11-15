package com.example.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val INJECTED_BY_HARNESS_KEY = "com.example.riadai.INJECTED_DEBUG_TOKEN"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val prefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )

            if (!debugToken.isNullOrEmpty()) {
                // Synchronous commit() is required before DebugAppCheckProviderFactory reads it.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .putBoolean(INJECTED_BY_HARNESS_KEY, true)
                    .commit()
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(TAG, "App Check debug secret configured; DebugAppCheckProviderFactory installed.")
            } else if (
                prefs.getBoolean(INJECTED_BY_HARNESS_KEY, false) &&
                !prefs.getString(DEBUG_SECRET_KEY, null).isNullOrEmpty()
            ) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(TAG, "Using persisted harness App Check debug secret.")
            } else {
                val siteKey = resolveRecaptchaSiteKey(context)
                if (!siteKey.isNullOrBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "No harness debug token found; RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.w(TAG, "No debug token or recaptcha_site_key found; installing DebugAppCheckProviderFactory.")
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
                }
            }
        } else {
            val siteKey = resolveRecaptchaSiteKey(context)
            if (!siteKey.isNullOrBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found or blank.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

private fun resolveRecaptchaSiteKey(context: Context): String? {
    val resId = context.resources.getIdentifier(
        RECAPTCHA_SITE_KEY_RES,
        "string",
        context.packageName
    )
    if (resId == 0) return null
    return context.getString(resId).takeIf { it.isNotBlank() }
}
