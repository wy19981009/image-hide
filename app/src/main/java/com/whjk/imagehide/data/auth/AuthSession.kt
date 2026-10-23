package com.whjk.imagehide.data.auth

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内解锁会话：隐藏相册验证通过后，本次进程内不再反复弹。
 */
@Singleton
class AuthSession @Inject constructor() {
    @Volatile
    var hiddenUnlocked: Boolean = false

    fun clear() {
        hiddenUnlocked = false
    }
}
