package com.whjk.imagehide.domain.error

/**
 * 统一异常。
 *
 * - Repository / UseCase 不抛原生 Exception，全部 wrap 成 AppError
 * - UI 层 catch 后做 toast / dialog
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object PermissionDenied : AppError("未授权")
    object NoMedia : AppError("相册里没有内容")
    class IoFailed(cause: Throwable) : AppError("文件操作失败", cause)
    class SafDenied(cause: Throwable?) : AppError("用户取消了系统确认", cause)
    class Unknown(cause: Throwable) : AppError("未知错误", cause)
}
