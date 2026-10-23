package com.whjk.imagehide.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.whjk.imagehide.ui.albumlist.AlbumListScreen
import com.whjk.imagehide.ui.detail.AlbumDetailScreen
import com.whjk.imagehide.ui.lock.PinLockScreen
import com.whjk.imagehide.ui.permission.PermissionScreen
import com.whjk.imagehide.ui.settings.SettingsScreen
import com.whjk.imagehide.ui.viewer.MediaViewerScreen

object Routes {
    const val PERMISSION = "permission"
    const val LOCK = "lock"
    const val ALBUM_LIST = "albums"
    /** 路径参数传递 startIndex，避免 query 解析丢参数导致永远打开第 0 张 */
    const val ALBUM_DETAIL = "albums/{bucketId}?name={name}&hidden={hidden}"
    const val MEDIA_VIEWER = "viewer/{albumId}/{startIndex}?hidden={hidden}"
    const val SETTINGS = "settings"

    fun albumDetail(bucketId: Long, name: String, hidden: Boolean = false): String {
        val encoded = Uri.encode(name)
        return "albums/$bucketId?name=$encoded&hidden=$hidden"
    }

    fun mediaViewer(albumId: Long, startIndex: Int, hidden: Boolean = false) =
        "viewer/$albumId/$startIndex?hidden=$hidden"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.PERMISSION) {
        composable(Routes.PERMISSION) {
            PermissionScreen(
                onGranted = {
                    nav.navigate(Routes.LOCK) {
                        popUpTo(Routes.PERMISSION) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.LOCK) {
            PinLockScreen(
                onUnlocked = {
                    nav.navigate(Routes.ALBUM_LIST) {
                        popUpTo(Routes.LOCK) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.ALBUM_LIST) {
            AlbumListScreen(
                onAlbumClick = { bucketId, name, hidden ->
                    nav.navigate(Routes.albumDetail(bucketId, name, hidden))
                },
                onSettingsClick = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(
            route = Routes.ALBUM_DETAIL,
            arguments = listOf(
                navArgument("bucketId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("hidden") { type = NavType.StringType; defaultValue = "false" }
            )
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getString("bucketId")?.toLongOrNull() ?: 0L
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            val hidden = backStackEntry.arguments?.getString("hidden")?.toBooleanStrictOrNull() ?: false
            AlbumDetailScreen(
                bucketId = bucketId,
                albumName = name,
                hidden = hidden,
                onMediaClick = { startIndex ->
                    nav.navigate(Routes.mediaViewer(bucketId, startIndex, hidden))
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = Routes.MEDIA_VIEWER,
            arguments = listOf(
                navArgument("albumId") { type = NavType.LongType },
                navArgument("startIndex") { type = NavType.IntType },
                navArgument("hidden") { type = NavType.StringType; defaultValue = "false" }
            )
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
            val startIndex = backStackEntry.arguments?.getInt("startIndex") ?: 0
            val hidden = backStackEntry.arguments?.getString("hidden")?.toBooleanStrictOrNull() ?: false
            MediaViewerScreen(
                albumId = albumId,
                startIndex = startIndex,
                hidden = hidden,
                onBack = { nav.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
