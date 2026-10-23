<div align="center">

# 📷 ImageHide · 隐藏相册

**把选中的相册从系统相册里"搬"到 APP 私有目录，系统相册、微信、抖音全部失明。**

[![Platform](https://img.shields.io/badge/platform-Android%2013%2B-3DDC84?logo=android&logoColor=white)](#-screenshots)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)](#-tech-stack)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.10-4285F4?logo=jetpackcompose&logoColor=white)](#-tech-stack)
[![License](https://img.shields.io/github/license/wy19981009/image-hide)](#-license)
[![Stars](https://img.shields.io/github/stars/wy19981009/image-hide?style=social)](#)

[English](#-imagehide--hide-your-photo-albums-from-the-system) · [简体中文](#-imagehide--隐藏相册)

</div>

---

# 🇺🇸 ImageHide · Hide Your Photo Albums from the System

> **TL;DR** — An open-source Android app that moves selected photo albums out of the system gallery, into the app's private storage. After hiding, no other app (WeChat, TikTok, system gallery) can see them; only ImageHide itself can.

## ✨ Features

- 🗂️ **Whole-album hide** — hide an entire system album in one tap
- 🔄 **Whole-album restore** — pick the destination folder, restore in one go
- 🔍 **Per-album browsing** — view hidden photos and videos inside the app
- 🔐 **PIN / biometric lock** — optional second-factor before opening the app or any hidden album
- ⚡ **SAF batched delete** — 100 items per system confirmation instead of 100 pop-ups
- 🛡️ **Encrypted local storage** (optional) — AES-256-GCM via AndroidKeyStore
- 🚀 **System-level thumbnails** — uses `ContentResolver.loadThumbnail` and `ThumbnailUtils` to avoid full-image decode when scrolling
- 📱 **Android 13+** (minSdk 33, targetSdk 35), tested on Xiaomi 14 / HyperOS

## 📸 Screenshots

> Drop in real screenshots before publishing. Until then, placeholders are shown.

| Album list | Hide a whole album | Hidden album inside the app | Settings |
| --- | --- | --- | --- |
| _placeholder_ | _placeholder_ | _placeholder_ | _placeholder_ |

Put PNG/JPG files under `docs/screenshots/` and replace the table above with:

```markdown
| ![](docs/screenshots/01-albums.png) | ![](docs/screenshots/02-hide.png) | ![](docs/screenshots/03-hidden.png) | ![](docs/screenshots/04-settings.png) |
```

## 🎬 How it works

```
User taps "Hide" on an album
  ↓
Copy all media files to filesDir/hidden/{bucketId}/
  ↓
Ask the system for batch-delete permission (SAF)
  ↓
SAF grants → MediaStore rows removed
  ↓
The album is invisible to every other app
```

The app never touches the user's original files before they are copied. If a hide is cancelled mid-way, the partial copy is left on disk and can be safely re-tried or cleaned from the hidden-album view.

## 🧰 Tech stack

| Layer | Choice |
| --- | --- |
| Language | Kotlin 2.0.20 |
| UI | Jetpack Compose (BOM 2024.10) + Material 3 |
| State | Hilt + ViewModel + StateFlow + SharedFlow |
| Persistence | Room 2.6.1, DataStore Preferences |
| Background work | WorkManager 2.9.1 + HiltWorker |
| Media | Media3 ExoPlayer 1.4.1, telephoto 0.13.0 |
| Imaging | Coil 2.7.0 + custom `Fetcher.Factory` (system thumbnails) |
| Auth | androidx.biometric 1.2.0-alpha05 |
| Build | AGP 8.6.1, Gradle 8.7, JDK 17, KSP 2.0.20-1.0.25 |

## 🏗️ Architecture

Single-Activity + Compose. ViewModels own all hide/restore flows; the UI only emits user intent and renders state. SAF dialogs are awaited via `CompletableDeferred` in the ViewModel — no WorkManager round-trip needed.

```
ui/albumlist/   AlbumListScreen + AlbumListViewModel   ← list of system / hidden albums
ui/detail/      AlbumDetailScreen + AlbumDetailViewModel
ui/viewer/      MediaViewerScreen + PhotoPage + VideoPlayer
ui/lock/        PinLockScreen                          ← app entry lock
ui/settings/    SettingsScreen                         ← PIN, biometric, all-files access
worker/         HideAlbumWorker / SingleHideWorker     ← legacy, kept for background resilience
data/
  auth/         BiometricHelper + AuthSession
  crypto/       CryptoManager (AES-256-GCM) + EntryPoint
  filemove/     FileMover (copy / write-back) + PrivateDirLayout
  mediastore/   MediaStoreDataSource + MediaStoreQuery
  thumb/        MediaThumb + MediaThumbFetcher (system thumbnails)
  room/         AppDatabase + entities
  repository/   HiddenRepositoryImpl / MediaRepositoryImpl
  datastore/    UserPrefs (PIN hash, biometric flag, lock flags)
```

## 🚀 Build

```bash
# Requirements
# - JDK 17
# - Android SDK 35
# - Android device or emulator with API 33+

git clone https://github.com/wy19981009/image-hide.git
cd image-hide
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` must point to your Android SDK:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

## 🛠️ Permissions

The app requests the bare minimum:

- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (Android 13+)
- `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+ partial access)
- `POST_NOTIFICATIONS` (for hide / restore progress)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`
- `USE_BIOMETRIC` (optional, only if you enable biometric lock)

For batch delete without 100 system pop-ups, you can also opt in to **All files access** in Settings. The app explains why before sending you to the system page.

## 🔐 Security model

- PIN is **never** stored in plain text. Only `SHA-256(salt + pin)` and the salt itself live in DataStore.
- Encryption is **opt-in** and uses AES-256-GCM with a key sealed in AndroidKeyStore. Decryption happens lazily per file (`EncryptedFileOpener.openForView`) and is cached in `cacheDir`.
- The app does **not** exfiltrate anything. No analytics, no telemetry, no network calls.

## 🧪 Device self-check (what to verify after installing)

1. App requests media permission on first run.
2. System-album list shows all your real albums with thumbnail + count.
3. Tap "Hide" on an album → progress dialog → batch delete confirmation → album disappears from system gallery.
4. Open WeChat / TikTok / system gallery → that album is **not** visible.
5. Open ImageHide → "Hidden" tab → album is there. Tap to view photos and videos.
6. Tap "Restore" → pick destination folder → progress dialog → media reappears in system gallery.
7. Optional: enable PIN in Settings, restart app → PIN gate appears.

## ❓ FAQ

**Q: Will the app delete my photos if I uninstall it?**
A: Hidden files live under the app's private `filesDir`, which is wiped on uninstall. Your originals are already back in the system gallery.

**Q: Does it work on non-Xiaomi phones?**
A: It is tested on Xiaomi 14 / HyperOS. In theory it works on any Android 13+ device that exposes media via the standard MediaStore. Some OEM ROMs add their own confirmations on top; if you see one, that's the system, not the app.

**Q: Can I hide just one photo instead of a whole album?**
A: Not yet — single-item hide is planned for the next release.

**Q: Is encryption enabled by default?**
A: No. It's a setting; turning it on re-encrypts future hides. Earlier files stay in plain until you hide them again.

**Q: Why is `READ_MEDIA_VISUAL_USER_SELECTED` requested?**
A: It's an Android 14+ permission that lets the user grant access to only some photos. The app doesn't currently use partial access; the permission is reserved for the per-item hide feature.

## 🗺️ Roadmap

- [ ] Single-photo / single-video hide (the architecture is already there)
- [ ] Search inside hidden albums
- [ ] Custom album rename
- [ ] Multi-window support
- [ ] Material You dynamic color (already enabled on Android 12+)

## 🤝 Contributing

Bug reports and PRs are welcome. For larger changes please open an issue first so we can agree on the direction.

```bash
# Format & lint
./gradlew ktlintCheck
./gradlew :app:assembleDebug
```

Please do not commit build artifacts, IDE files, or `local.properties`. They are already in `.gitignore`.

## 📄 License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE).

## 🙏 Acknowledgements

- Jetpack Compose team for the best UI toolkit on Android
- saket for [telephoto](https://github.com/saket/telephoto)
- Coil team for the most ergonomic image loader on Android
- The Media3 / ExoPlayer team
- Everyone in the Android community who writes about scoped storage in plain English

---

# 🇨🇳 隐藏相册 · 把相册从系统相册里"藏"起来

> **一句话简介** — 开源 Android APP，把选中的相册从系统相册搬到本 APP 私有目录。隐藏后系统相册、微信、抖音都看不到，只能在 APP 内查看。

## ✨ 特性

- 🗂️ **整相册隐藏** — 一键把整本相册从系统相册里移走
- 🔄 **整相册还原** — 选择目标文件夹，一键写回系统相册
- 🔍 **隐藏相册内浏览** — 图、视频全格式支持
- 🔐 **PIN / 指纹锁** — 可选二次验证（开 APP / 打开隐藏相册）
- ⚡ **SAF 批量删除** — 每 100 张共用一次系统确认，不再一次次弹
- 🛡️ **本地加密**（可选）— AES-256-GCM，密钥由 AndroidKeyStore 保管
- 🚀 **系统级缩略图** — 走 `ContentResolver.loadThumbnail` + `ThumbnailUtils`，网格滑动不卡
- 📱 **Android 13+**（minSdk 33 / targetSdk 35），小米 14 / HyperOS 真机验证

## 🎬 原理

```
用户点击"隐藏"按钮
  ↓
把所有媒体文件复制到 filesDir/hidden/{bucketId}/
  ↓
申请系统批量删除授权（SAF）
  ↓
SAF 通过 → MediaStore 行被删除
  ↓
整本相册从系统相册 / 微信 / 抖音里消失
```

APP 不会先动原文件，全部复制完成再申请删除。中途取消会留下已复制的文件，可在"已隐藏"里重试或手动清理。

## 🛠️ 技术栈

Kotlin 2.0.20 · Jetpack Compose · Material 3 · Hilt · Room 2.6.1 · DataStore · WorkManager 2.9.1 · Media3 ExoPlayer 1.4.1 · Coil 2.7.0 · telephoto 0.13.0 · androidx.biometric · AGP 8.6.1 / Gradle 8.7 / KSP 2.0.20

## 🚀 编译

```bash
git clone https://github.com/wy19981009/image-hide.git
cd image-hide
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17、Android SDK 35、API 33+ 真机或模拟器。`local.properties` 需要指向 Android SDK 路径。

## 🛠️ 权限

只申请必要的：

- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`（Android 13+）
- `READ_MEDIA_VISUAL_USER_SELECTED`（Android 14+ 部分授权，为单张隐藏预留）
- `POST_NOTIFICATIONS`（进度通知）
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`
- `USE_BIOMETRIC`（可选）

想去掉 100 次系统确认弹窗？在设置里授权**所有文件访问**，APP 会先告诉你为什么。

## 🔐 安全模型

- PIN 永不明文存储，只存 `SHA-256(salt + pin)` + 盐
- 加密可选，AES-256-GCM + AndroidKeyStore；解密按需发生（`EncryptedFileOpener.openForView`），缓存在 cacheDir
- 零网络请求、零埋点、零上传

## 🤝 贡献

欢迎 PR / Issue。大改动请先开 issue 讨论方向。

```bash
./gradlew :app:assembleDebug    # 编译验证
```

请勿提交 `build/`、`.idea/`、`local.properties`、`hs_err_*.log`、`replay_*.log`。`.gitignore` 已配好。

## 📄 License

MIT — 见 [LICENSE](LICENSE)。

## 🙏 致谢

Jetpack Compose · saket / telephoto · Coil · Media3 / ExoPlayer · Android 社区里把 Scoped Storage 讲明白的人。

---

<div align="center">

<sub>Made with ❤️ in Yunnan. 如果这个项目帮到你了，麻烦点个 ⭐ 鼓励一下作者。</sub>

</div>
