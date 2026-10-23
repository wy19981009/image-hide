# 隐藏相册 (ImageHide)

> Android 13+ / Kotlin + Compose / 移动模式 / 适配小米 14 HyperOS

把系统相册中选定的相册整体移走（复制到 APP 私有目录 + 删除 MediaStore 记录），
让系统相册、微信、抖音等所有第三方 APP 全部失明；本 APP 内可正常浏览。

---

## 工程状态

✅ 全部 7 步路线已完成，可编译骨架 + 全部业务实装。

| 模块 | 文件 | 状态 |
|---|---|---|
| 权限 | `PermissionScreen`, `PermissionViewModel`, `PermissionChecker` | ✅ |
| 扫描 | `MediaStoreDataSource.scanSystemAlbums/listSystemMediaByBucket` | ✅ |
| 隐藏 | `FileMover.copyToPrivate + createDeleteRequest` + `HideAlbumWorker` 4 阶段 | ✅ |
| 还原 | `RestoreAlbumWorker` 4 阶段 + `MediaStoreQuery.findPendingByName` | ✅ |
| 查看器 | `MediaViewerScreen` HorizontalPager + `ZoomableAsyncImage` + `ExoPlayer` | ✅ |
| 设置 | `SettingsScreen` PIN 码 / 加密 / 还原全部 | ✅ |
| PIN 锁屏 | `PinLockScreen` + DataStore 哈希 | ✅ |
| 加密 | `CryptoManager` AES-256-GCM + AndroidKeyStore | ✅ |

---

## 真机自检清单

> 拿到工程后请按顺序跑一遍，每步必须 ✅ 才进下一步。

### 阶段 A：工程能起来

- [ ] Android Studio 打开 `E:\wy-project\play\image-hide`
- [ ] 第一次 sync：Gradle 拉依赖不报错（首次约 5-10 分钟）
- [ ] 工具栏选择 `app` 配置 + 你的小米 14，**小米 14 必须开启 USB 调试**
- [ ] 点 ▶ 运行，Logcat 出现 `ImageHide` Application 启动日志
- [ ] App 在桌面显示图标"隐藏相册"

### 阶段 B：权限

- [ ] 首次启动 → 权限引导页正常显示
- [ ] 点"授权并继续" → 系统弹窗出现 **3 个权限**（图片 / 视频 / 部分授权）
- [ ] 选"允许访问所有图片和视频" → 自动跳下一屏
- [ ] 若拒绝 → 提示错误文案 + "去系统设置"按钮，按钮能跳到系统设置
- [ ] 重启 App → 不用再授权（已记下）

### 阶段 C：扫描

- [ ] 相册列表页（系统 Tab）显示你手机里的所有相册
- [ ] 相册按媒体数倒序（相机、截图、微信、QQ 通常在前）
- [ ] 每个相册有封面图（Coil 加载）
- [ ] 数字显示媒体总数（与系统相册 APP 对照一致）
- [ ] 顶部刷新按钮工作

### 阶段 D：隐藏

- [ ] 选一个小相册（比如"截图"），点隐藏按钮
- [ ] 顶部进度条 0% → 100%（复制阶段）
- [ ] **系统弹窗**："允许隐藏相册 删除 N 个文件？" → 选"删除"
- [ ] 进度条继续推进到 100% → 隐藏 Tab 多一个"截图"
- [ ] 打开系统相册 APP → 截图相册消失
- [ ] 打开微信、抖音 → 也找不到截图中被隐藏的图
- [ ] 回本 APP → 隐藏 Tab 看到"截图"

### 阶段 E：查看器

- [ ] 隐藏 Tab → 点"截图" → 进入网格
- [ ] 网格里看到所有被隐藏的图（从私有目录读）
- [ ] 点一张图 → 全屏查看，双指缩放、双击放大、拖动都工作
- [ ] 左右滑切换下一张 / 上一张
- [ ] 如果有视频 → 点视频进入 → 自动播放 + 控件条
- [ ] 切到下一张视频时，上一张视频停止播放（没继续出声）

### 阶段 F：还原

- [ ] 隐藏 Tab → 点"截图"的"显示"按钮
- [ ] 进度条 0% → 50%（PREPARE）
- [ ] **系统弹窗**："允许隐藏相册 写入 N 个文件？" → 选"允许"
- [ ] 进度 50% → 100%（FINALIZE 写文件）
- [ ] 隐藏 Tab 少一项，系统 Tab 重新出现
- [ ] 打开系统相册 → 截图相册回来，**图/视频在原本的文件夹里**（不是 Pictures 根目录）
- [ ] 缩略图、日期、位置都对（DATE_TAKEN 还原成功）
- [ ] 私有目录已清（用 adb shell ls /data/data/com.whjk.imagehide/files/hidden/ 应为空或被删）

### 阶段 G：设置

- [ ] 设置 → 打开 PIN 码 → 输两次 → 保存
- [ ] 重启 App → 进入锁屏页 → 输错 PIN → 提示错误
- [ ] 输对 PIN → 进入
- [ ] 设置 → 打开加密 → 隐藏一个新相册
- [ ] `adb shell ls /data/data/com.whjk.imagehide/files/hidden/{bucketId}/` 应看到 `.enc` 文件
- [ ] 隐藏 Tab 打开该相册 → 仍能正常看图（解密成功）
- [ ] 设置 → 还原全部 → 看到所有隐藏相册被批量还原
- [ ] 设置 → 关闭 PIN → 重启 App → 不再要求 PIN

### 阶段 H：边界

- [ ] 隐藏一个相册过程中切到后台 → 通知栏显示进度
- [ ] App 被系统杀掉 → 通知栏还在点回来 → 任务继续
- [ ] 隐藏 100+ 图的大相册 → 不卡死，进度条流畅
- [ ] 同名文件被隐藏两次 → 第二次走 uniqueFile 自动加后缀，不覆盖
- [ ] 删除私有目录里的某文件（用 adb）后还原 → 那张图丢，**Worker 跳过继续**（不中断整批）

---

## 调试技巧

```bash
# 查看私有目录
adb shell run-as com.whjk.imagehide ls -la files/hidden/

# 查看 Room 数据库
adb shell run-as com.whjk.imagehide cat databases/image_hide.db > /tmp/dump.db
# 用 sqlite3 打开

# 看 Worker 日志
adb logcat -s WM-WorkerWrapper

# 拉 Worker 通知
adb shell dumpsys jobscheduler | grep imagehide
```

---

## 已知限制 / 后续优化

- [ ] 视频首帧缩略图（用 coil-video 抽帧）
- [ ] Worker 失败后的重试策略（指数退避）
- [ ] 多相册并发 Worker（目前一相册一 Worker 串行）
- [ ] PIN 输错 N 次锁定
- [ ] 假入口（计算器密码、拨号盘密码）
- [ ] 加密文件跨设备同步（需要把 KeyStore 密钥备份到 Google Drive 或类似服务）
- [ ] App 升级时数据迁移（fallbackToDestructiveMigration 当前是兜底，正式版要写 Migration）
- [ ] 大图 / 视频分页加载

---

## 目录结构

```
image-hide/
├── docs/
│   └── 01-architecture.md        # 架构设计 + 文件清单
├── gradle/
│   └── libs.versions.toml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/whjk/imagehide/
│       │   ├── ImageHideApp.kt
│       │   ├── MainActivity.kt
│       │   ├── data/             # MediaStore / Room / Crypto / FileMover / DataStore
│       │   ├── domain/           # Model / UseCase / Repository 接口 / Error
│       │   ├── di/               # Hilt Module
│       │   ├── ui/               # Compose Screen + ViewModel
│       │   │   ├── albumlist/    # 相册列表
│       │   │   ├── detail/       # 网格
│       │   │   ├── lock/         # PIN 锁屏
│       │   │   ├── nav/          # 路由
│       │   │   ├── permission/   # 权限引导
│       │   │   ├── settings/     # 设置
│       │   │   ├── theme/        # 主题
│       │   │   └── viewer/       # 查看器
│       │   └── worker/           # Hide/Restore Worker + ProgressNotifier
│       └── res/
└── README.md
```

---

## 关键技术点（已实现）

1. **MediaStore + SAF 双协议** —— 隐藏用 `createDeleteRequest`，还原用 `createWriteRequest`
2. **Room** —— 隐藏相册元数据、媒体元数据、CASCADE 自动清理
3. **WorkManager 4 阶段** —— COPY/AWAIT_SAF/FINALIZE/DONE，每阶段 setProgress 给 UI 监听
4. **Hilt 全栈** —— Application / ViewModel / Worker 全部注入
5. **Coil + telephoto** —— 双指缩放、双击放大
6. **Media3 ExoPlayer** —— 视频播放 + 跨页 release
7. **AndroidKeyStore + AES-256-GCM** —— 加密开关开启时私有目录文件 .enc
8. **DataStore Preferences** —— PIN 哈希 + 加密开关持久化
