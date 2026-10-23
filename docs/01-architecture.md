# 隐藏相册 APP · 架构与文件清单

> Android 13+ (minSdk 33, targetSdk 35) · Kotlin + Jetpack Compose · 移动模式
> 适配机型：小米 14 (HyperOS, Android 14/15)

---

## 一、核心原理

Android 不提供"对相册打码"的官方 API。要让系统相册、微信、抖音等所有第三方 APP 都看不到某相册中的媒体，**唯一可靠路径**是：

> **把媒体文件从 MediaStore 可见区移走，存到 APP 私有目录。**

第三方 APP 全部依赖 `MediaStore` 读取媒体；它查不到的项，第三方一律失明。还原时再通过 `MediaStore.createWriteRequest` 写回原 `RELATIVE_PATH` 并触发系统重新索引。

---

## 二、依赖版本（Gradle 坐标，标清版本）

```kotlin
// libs.versions.toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
compose-bom = "2024.10.00"
room = "2.6.1"
hilt = "2.51.1"
work = "2.9.1"
media3 = "1.4.1"
coil = "2.7.0"
lifecycle = "2.8.6"
navigation = "2.8.2"
datastore = "1.1.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.13.1" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.3" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-work = { group = "androidx.hilt", name = "hilt-work", version = "1.2.0" }
hilt-work-compiler = { group = "androidx.hilt", name = "hilt-compiler", version = "1.2.0" }
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
coil-video = { group = "io.coil-kt", name = "coil-video", version.ref = "coil" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

### app/build.gradle.kts 关键依赖

```kotlin
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    implementation(libs.hilt.work); ksp(libs.hilt.work.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
```

### AndroidManifest.xml 权限声明

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<!-- Android 14+ 部分授权（推荐，让用户只选"全部允许"或"指定媒体"） -->
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
<!-- 后台任务需要前台服务通知 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

---

## 三、文件清单（按包组织）

```
app/src/main/java/com/whjk/imagehide/
├── ImageHideApp.kt                        # Application + Hilt + WorkManager 配置
├── MainActivity.kt                        # 唯一 Activity，承载 Compose Nav
│
├── ui/
│   ├── theme/                             # Material3 主题、颜色、字体
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   ├── nav/
│   │   └── AppNav.kt                      # NavHost 路由定义
│   ├── permission/
│   │   ├── PermissionScreen.kt            # 权限引导页（首次启动）
│   │   └── PermissionViewModel.kt
│   ├── albumlist/
│   │   ├── AlbumListScreen.kt             # 相册列表（系统相册 + 隐藏相册 Tab）
│   │   ├── AlbumListViewModel.kt
│   │   └── AlbumCard.kt                   # 单个相册卡片
│   ├── detail/
│   │   ├── AlbumDetailScreen.kt           # 单个相册内的媒体网格
│   │   ├── AlbumDetailViewModel.kt
│   │   └── MediaThumbnail.kt
│   ├── viewer/
│   │   ├── MediaViewerScreen.kt           # 看图 / 看视频全屏页
│   │   ├── MediaViewerViewModel.kt
│   │   ├── PhotoPage.kt                   # PhotoView / ZoomableImage
│   │   └── VideoPlayer.kt                 # Media3 ExoPlayer 包装
│   ├── settings/
│   │   ├── SettingsScreen.kt              # 设置（PIN 码、加密开关、还原全部）
│   │   └── SettingsViewModel.kt
│   └── components/
│       ├── ProgressDialog.kt
│       └── EmptyState.kt
│
├── domain/
│   ├── model/
│   │   ├── Album.kt                       # 相册数据类（系统 / 隐藏）
│   │   ├── MediaItem.kt                   # 媒体（图片 / 视频）
│   │   └── HideProgress.kt                # 进度上报
│   ├── usecase/
│   │   ├── ScanAlbumsUseCase.kt           # 扫描系统相册
│   │   ├── HideAlbumUseCase.kt            # 触发隐藏任务
│   │   ├── RestoreAlbumUseCase.kt         # 触发还原任务
│   │   └── GetHiddenMediaUseCase.kt       # 读私有目录
│   ├── repository/
│   │   ├── AlbumRepository.kt             # 接口
│   │   ├── MediaRepository.kt             # 接口
│   │   └── HiddenRepository.kt            # 接口
│   └── error/
│       └── AppError.kt                    # 密封异常类
│
├── data/
│   ├── mediastore/
│   │   ├── MediaStoreDataSource.kt        # ContentResolver 读写
│   │   └── MediaStoreMappers.kt
│   ├── filemove/
│   │   ├── FileMover.kt                   # 核心：复制到私有目录 + SAF 删
│   │   └── PrivateDirLayout.kt            # 私有目录布局规则
│   ├── room/
│   │   ├── AppDatabase.kt
│   │   ├── entity/
│   │   │   ├── HiddenAlbumEntity.kt
│   │   │   └── HiddenMediaEntity.kt
│   │   └── dao/
│   │       ├── HiddenAlbumDao.kt
│   │       └── HiddenMediaDao.kt
│   ├── permission/
│   │   └── PermissionChecker.kt
│   ├── datastore/
│   │   └── UserPrefsDataStore.kt          # PIN 码 / 加密开关 / 首次授权
│   └── repository/
│       ├── AlbumRepositoryImpl.kt
│       ├── MediaRepositoryImpl.kt
│       └── HiddenRepositoryImpl.kt
│
├── worker/
│   ├── HideAlbumWorker.kt                 # 移动 + 删 MediaStore + 落库
│   ├── RestoreAlbumWorker.kt              # 写回 MediaStore + 清私有目录
│   ├── ProgressNotifier.kt                # 前台服务 + 通知栏进度
│   └── WorkScheduler.kt                   # 唯一/串行/重试策略
│
└── di/
    ├── AppModule.kt                       # ApplicationContext / ContentResolver
    ├── DataModule.kt                      # DataSource / DataStore
    ├── RepositoryModule.kt                # 绑定 Repository 实现
    └── WorkerModule.kt                    # HiltWorkerFactory
```

### 资源文件

```
app/src/main/res/
├── values/
│   ├── strings.xml
│   ├── colors.xml
│   └── themes.xml
├── values-night/
│   └── themes.xml
├── drawable/
│   └── ic_launcher_foreground.xml
├── mipmap-anydpi-v26/
│   └── ic_launcher.xml / ic_launcher_round.xml
└── xml/
    ├── data_extraction_rules.xml
    └── backup_rules.xml
```

---

## 四、关键数据流

### 4.1 扫描相册

```kotlin
// MediaStoreDataSource.scanAlbums()
val projection = arrayOf(
    MediaStore.Files.FileColumns.BUCKET_ID,
    MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
    MediaStore.Files.FileColumns._ID,
    MediaStore.Files.FileColumns.MIME_TYPE
)
val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
val args = arrayOf(
    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
)
val groupBy = "${MediaStore.Files.FileColumns.BUCKET_ID}"

contentResolver.query(
    MediaStore.Files.getContentUri("external"),
    projection, selection, args,
    "$groupBy DESC"
)?.use { c ->
    val groups = mutableMapOf<Long, Album>()
    while (c.moveToNext()) {
        val bucketId = c.getLong(c.getColumnIndexOrThrow(BUCKET_ID))
        val name = c.getString(c.getColumnIndexOrThrow(BUCKET_DISPLAY_NAME)) ?: "未命名"
        groups.getOrPut(bucketId) { Album(bucketId, name, 0, emptyList()) }
    }
    // 再查一次取每相册最新一张作封面
}
```

### 4.2 隐藏（核心）

```kotlin
// HideAlbumWorker.doWork() 伪代码
1. filesDir/hidden/{bucketId}/ 创建
2. contentResolver.openInputStream(uri) → 复制到私有目录
3. 收集所有待删 uri → MediaStore.createDeleteRequest(resolver, uris)
4. 启动 SAF Intent，ActivityResultContracts.StartIntentSenderForResult
5. 用户在系统弹窗确认 → 删成功
6. 写 Room: HiddenAlbumEntity(bucketId, originalName, hiddenAt, count)
7. 通知栏 setProgress(100)
```

### 4.3 还原

```kotlin
// RestoreAlbumWorker.doWork() 伪代码
1. 读 Room 拿该相册所有 HiddenMediaEntity
2. 每张：MediaStore.createWriteRequest(resolver, collectionWithDetails)
   - DISPLAY_NAME = 原文件名
   - RELATIVE_PATH = 原路径（来自备份字段）
   - IS_PENDING = 1
3. 拿到目标 uri，打开输出流，从私有目录 copy 进去
4. IS_PENDING = 0 提交
5. 私有目录文件 delete()
6. Room 删除该相册记录
```

### 4.4 查看器

```kotlin
// MediaViewerScreen
when (item) {
    is ImageItem -> ZoomableImage(src = File(privatePath))
    is VideoItem -> VideoPlayer(uri = Uri.fromFile(File(privatePath)))
}
```

---

## 五、常见坑（提前说在前面）

| 现象 | 原因 | 解法 |
|---|---|---|
| 隐藏后系统相册还能看到 | 只改了 DB 记录，没真删 MediaStore | 必须 `createDeleteRequest` 走系统弹窗 |
| 隐藏后微信还能看到 | 同上，微信也走 MediaStore | 同上，删完会同步 |
| Android 11+ 直接 `File.delete()` 失败 | Scoped Storage 限制 | 必须走 SAF IntentSender |
| 视频被清后用户反悔 | 私有目录 + DB 都干净 | 还原时 RELATIVE_PATH/DISPLAY_NAME/MIME_TYPE/DATE_TAKEN 全部备份到 Room |
| 进度更新不显示 | Worker 没用 `setForeground` | 用 `setForeground(ForegroundInfo)` + 通知 ID |
| 后台被杀任务中断 | WorkManager 默认重试可能乱序 | 用 `WorkInfo.State` 监听，单相册串行 |
| 部分授权后扫描不到 | `READ_MEDIA_VISUAL_USER_SELECTED` 只给部分照片 | 提供"补充授权"按钮拉系统弹窗 |
| 小米 14 通知没进度 | MIUI 优化后台 | 引导用户加自启动 + 锁后台 |
| 视频时长 0 | MediaStore 没同步 | 还原后等 200ms 再查，必要时主动 `scanFile` 触发 |
| Room 写入失败 | 主线程 IO | 全部用 suspend + Room KTX 协程 |

---

## 六、上手步骤（下一步行动）

1. **本轮确认**（已通过）：移动模式 / 必须看图视频 / minSdk 33 / 架构图
2. **下一轮**：生成 Android Studio 工程骨架（Gradle + Manifest + 4 个空包 + Application + MainActivity）
3. **再下一轮**：写 `PermissionScreen` + `PermissionChecker` + `MediaStoreDataSource.scanAlbums`，跑通扫描
4. **再下一轮**：`FileMover` + `HideAlbumWorker`，能隐藏一个相册
5. **再下一轮**：`MediaViewerScreen`（Coil + Media3），能看图视频
6. **再下一轮**：`RestoreAlbumWorker`，能还原
7. **再下一轮**：`SettingsScreen` + PIN 码 + 加密开关

每轮先 commit，再进下一轮。

---

## 七、本轮已交付物

- ✅ 隐藏语义定调（移动模式）
- ✅ SDK / 依赖版本锁死
- ✅ 权限清单（Manifest）
- ✅ 完整文件清单（按 4 个包组织）
- ✅ 关键代码骨架（扫描 / 隐藏 / 还原 / 查看）
- ✅ 常见坑清单
- ✅ 7 步实施路线
