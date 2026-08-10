package com.nexlock.agent.service

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Awaits the current FCM registration token without pulling in the extra
 * kotlinx-coroutines-play-services dependency just for this one call.
 */
suspend fun currentFcmToken(): String? = suspendCancellableCoroutine { cont ->
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { cont.resumeWith(Result.success(it)) }
        .addOnFailureListener { cont.resumeWith(Result.success(null)) }
}
