# 相机 6.0.002710.2 适配分析

分析日期：2026-10-05。对象为用户提供的 APK，而非其他机型的同名版本。

- 包名：`com.android.camera`
- versionCode：`600027102`
- versionName：`6.0.002710.2`
- minSdk：29；targetSdk：34
- SHA-256：`f3b220bddf804778d25e2173d68aa7ef500da0750f5aa126b5c02f862727c637`
- 模块代码基线：`03eff0a`，版本 1.7。
- 用户症状：相机设置中没有街拍入口。
- 用户要求：后续 Hook 使用 libxposed API 102。

## 结论

当前模块无法通过只更新混淆名称来适配这个 APK。旧息屏街拍链路在此 APK 中不再可用；设置页面和资源访问也有独立的兼容性问题。

静态检查足以证明当前查找规则无法命中，尚未进行真机验证。不能将“恢复设置项”视为“恢复息屏拍摄”。

## 1. 设置 Hook 之前就会初始化失败

`CameraHooker.onFindMembers()` 首先注册 `CameraSnapMembersFinder`；`BaseFinder.onFinishLoadFinder()` 顺序执行所有 Finder。查找结束后，`BaseHookerWithDexKit` 才保存成员并调用 `startHook()`。

`CameraSnapMembersFinder.kt:41` 依赖日志字符串 `takeSnap: CameraDevice is opening or was already closed.` 查询 SnapCamera，随后直接使用 `!!` 和 `.first()`。

读取全部六个 DEX 的字符串表，没有这个字符串，也没有以下特征：

- `Lcom/android/camera/snap/`
- `SnapKeyReceiver`
- `CAMERA_KEY_BUTTON`
- `key_long_press_volume_down`
- `Street-snap`
- `shouldQuitSnap isNonUI = `
- `save picture failed `
- `onCameraOpened: exit`

新版 Manifest 也没有声明 `SnapKeyReceiver`、`SnapService` 或旧街拍广播 action。

因此，按当前代码重新扫描这个 APK 时会在前置查找失败，无法进入设置 Hook。即使跳过该异常，原系统 Hook 发往 `com.android.camera.snap.SnapKeyReceiver` 的显式广播也没有对应组件可以接收。

这些证据表明旧链路已移除或被不兼容的实现替代。DEX 内仍有 `StreetModule`、`EquipStreetModule`，它们是相机内的街拍模式，不能仅凭名称当作旧息屏街拍服务的替代品。

## 2. 设置分类已变化

新版仍保留以下类：

- `com.android.camera.fragment.settings.BasePreferenceFragment`
- `com.android.camera.fragment.settings.CameraPreferenceFragment`
- `com.android.camera.fragment.settings.CameraCommonPreferenceFragment`
- `com.android.camera.ui.PreviewListPreference`

`CameraPreferenceActivity.qi()` 创建拍照、录像、通用、进阶等设置标签页。

`CameraCommonPreferenceFragment.Gb()` 创建的分类包括：

- `category_common_setting_group1`
- `category_customization`
- `category_common_setting_group2`

旧 `SettingsHooker` 只尝试 `category_module_setting` 和 `category_photo_setting`，无法直接覆盖新版通用设置页。

新版 `BasePreferenceFragment.ue()` 是本 APK 的设置树重建方法，仍含 `fail to init PreferenceGroup` 日志。其流程是创建/清空 PreferenceScreen、调用 `Gb()` 创建条目，再执行 `vf()`、`kg()`、`bh()`。可将它作为本版本的查找锚点，在执行完原方法后注入设置项，但不应把混淆方法名 `ue` 固定用于所有版本。

此外，新版模式来源存储在 BasePreferenceFragment 的静态 int 字段，旧包装器按 `mFromWhere` 或实例 int 字段读取的假设不再成立。

## 3. 街拍文案仍在，但资源名被混淆

解码 resources.arsc 后确认：

| 资源名 | ID（仅此 APK） | 内容 |
| --- | --- | --- |
| `hgx` | `0x7f140c43` | 街拍模式 / Quick snap mode |
| `hgz` | `0x7f140c45` | 息屏状态下，长按音量下键即可拍照 |
| `hh0` | `0x7f140c46` | 街拍模式 B（录像） |
| `hh1` | `0x7f140c47` | 街拍模式 A（连拍） |
| `hh6` | `0x7f140c4c` | Picture |

DEX 中仍出现 `pref_camera_snap_*` 名称，但资源表采用混淆名称。原 `wrapper/camera/R.kt` 调用 `Resources.getIdentifier()` 查找原资源名的方式不能直接使用；对本 APK 查找原名将得到 0。

修复应提供模块自己的文案和配置值，或通过受验证的资源映射解析。不能把上表 ID 硬编码成通用版本适配方案。残留的字符串不证明拍摄组件仍存在。

## 4. API 102 迁移要求

已核对 Maven 发布的 `io.github.libxposed:api:102.0.0` 源码中的 `package-info.java`、`XposedModuleInterface.java` 和 `XposedInterface.java`。

- 使用 `compileOnly 'io.github.libxposed:api:102.0.0'`。
- 入口继承 `XposedModule`，在生命周期回调中初始化。
- 相机类加载完成后使用 `onPackageReady()` 和对应 ClassLoader；系统 Hook 使用 `onSystemServerStarting()`。
- 所有实际 Hook 改用 `hook(method).intercept(chain -> ...)`；执行原方法通过 `chain.proceed()`。移除旧 YukiHookAPI Hook 入口、KSP 入口生成器和 legacy Xposed API 82 的依赖。
- 注册文件放在 `src/main/resources/META-INF/xposed/`，包含 `java_init.list`、`module.prop`、`scope.list`。
- `module.prop` 设置 `minApiVersion=102`、`targetApiVersion=102`。
- 作用域使用 `com.android.camera` 和特殊系统作用域 `system`。现代 API 的 system_server 作用域不能照搬旧配置中的 `android`。
- 当前 `packagingOptions.resources.excludes += '/META-INF/**'` 会排除新入口文件，必须删除或缩小排除范围。
- 添加入口类保留规则，并用产物检查确认 APK 内确实存在现代入口文件。

只升级 Gradle 依赖或修改 Manifest 的最低版本，不能完成 API 102 迁移，也不能恢复被删除的街拍组件。

## 修复顺序

1. 拆开设置入口、系统按键、拍照和录像的初始化；记录每项能力和失败原因，避免辅助成员缺失阻断全部功能。
2. 将入口和实际 Hook 迁移到 API 102，替换现代注册文件，并验证构建产物。
3. 在新版通用设置页重建设置项，使用自己的文案和配置，按稳定生命周期或受验证的 DexKit 锚点定位注入时机。
4. 重建息屏街拍后台：按键接收、冷启动、拍照/录像、媒体保存、资源释放。原 SnapCamera 包装器本质上依赖宿主字段，并非可独立复用的完整拍摄实现。
5. 根据手机的 HyperOS/Android 版本适配系统按键派发。确认接收组件可从进程未运行状态启动，不能只依赖相机进程内的动态 receiver。
6. 真机验证长按触发、松键停止、锁屏状态、冷启动、图片/视频保存和普通相机使用。

## 验证边界与分析产物

已执行：APK Manifest 解码、六个 DEX 字符串表检查、资源表解码、设置 Activity/Fragment 和启动广播接收器定向反编译、API 102 发布源码核对。

分析产物位于被 Git 忽略的 `build/camera-analysis/`，包括 `evidence.json`、`manifest.txt`、定向反编译 Java 文件和 `decoded/resources/`。没有修改用户 APK，也未将反编译宿主代码加入模块源码。

本次完成静态定位和迁移设计，未实施功能修复、编译模块或宣称真机恢复。系统侧还需要手机型号、HyperOS/Android 版本、Xposed 框架版本；对应系统框架或日志可用于核对按键 Hook。

## 补充：与可用旧版 4.5.002830.0 的对照

用户提供了另一台手机的、已适配的相机 APK。它可作为行为和组件参考，但不能用来推断目标手机的系统按键实现、摄像头 ID 或厂商拍摄参数。

- versionCode：`450028300`
- minSdk：29；targetSdk：33
- SHA-256：`e7cd30a24339caf149cd6c14c02f62230b12364452b7722cde52617feb8ca771`

| 检查对象 | 旧版 4.5 | 新版 6.0 |
| --- | --- | --- |
| `com.android.camera.snap.*` 类型引用 | 存在，含完整四组件和监听器 | 六个 DEX 字符串表均未检出 |
| Manifest 中的 SnapKeyReceiver | 存在、导出、要求 `com.xiaomi.camera.AUX_CONTROL` 权限 | 未声明 |
| Manifest 中的 SnapService | 存在、未导出 | 未声明 |
| `miui.intent.action.CAMERA_KEY_BUTTON` | Manifest 和 DEX 都存在 | Manifest 和 DEX 都未检出 |
| `key_long_press_volume_down`、`Street-snap-*` | DEX 中存在 | DEX 中未检出 |
| 原模块使用的 SnapCamera/SnapTrigger 日志锚点 | 存在 | 未检出 |
| `pref_camera_snap_*` 资源原名 | resources.arsc 中保留 | 资源名被混淆，文案仍有残留 |

### 旧版实际工作流程

1. `SnapKeyReceiver.onReceive()` 校验街拍支持开关、广播 action、系统街拍配置和相机权限。
2. 屏幕熄灭且尚未运行时，Receiver 携带 `key_code`、`key_action`、`key_event_time` 启动前台 SnapService；运行中直接转交 SnapTrigger。
3. `SnapService.onCreate()` 获取部分 WakeLock，并创建前台通知。`onStartCommand()` 初始化存储、看门狗、SnapTrigger，以及亮屏/电源键和温控监听。
4. SnapTrigger 对音量下按下启动相机，对松键或电源键移除待执行任务并安排服务结束；拍照完成后继续调度下一张，间隔 200ms、上限 100 张。
5. SnapCamera 使用 Camera2、预览 Surface 和 JPEG ImageReader 拍摄；保存路径依赖旧版 `Storage.addImage()` 和 EXIF 工具，完成后回调 SnapTrigger。
6. 服务结束时销毁触发器和拍摄资源、取消监听并释放 WakeLock。

源码验证旧 `SnapCamera.initSnapType()` 无条件将 `mIsCamcorder` 设为 false，这正是项目中 `CameraSnapHooker` 修改该方法、增加录像包装器的原因。旧版并没有一份能直接搬入新版的完整街拍录像实现。

### 可以复用与需要改写的部分

可作为行为规范复用的部分：按键数据格式、按下/松开状态转换、连拍调度、亮屏停止、完成回调、看门狗和资源释放顺序。旧版 `SnapKeyReceiver` 和 `SnapService` 也提供了冷启动链路的明确参考。

需要重写或重新验证的部分：

- 旧 `SnapCamera.initCamera()` 固定使用 camera ID 0；重建应枚举并验证目标手机的后置相机、输出尺寸和录像能力。
- 图片尺寸、CameraCapabilities、EXIF、定位、存储及统计调用依赖旧版内部类，不能按旧类名或方法签名直接调用 6.0。
- 旧前台服务没有声明新版 Android 所需的 camera/microphone 类型；后台启动和息屏访问相机的条件需要结合目标系统验证。
- 新版现存的 `LaunchCameraBroadcastReceiver` 会唤醒屏幕并启动 Camera Activity，其行为与旧息屏街拍不同，不能直接替代 SnapKeyReceiver。
- 仅把旧类加入模块 ClassLoader，不会让它们成为新版相机 PackageManager 中已注册的 Receiver/Service。必须设计可被系统启动的实际后台组件，或完整实现并验证宿主组件注册和实例化适配。
- API 102 迁移仍须更换入口、实际 Hook 调用和打包规则；旧 APK 不改变此要求。

新增分析产物：`manifest-old.txt`、`evidence-old.json`、`old-SnapKeyReceiver.java`、`old-SnapService.java`、`old-SnapTrigger.java`、`old-SnapCamera.java`，均位于 `build/camera-analysis/`。旧 APK 也未被修改。

## 后续实施：2.0-beta1

用户确认开始修复后，已在本地实施独立重建方案；前文“未修改功能代码”描述的是分析阶段。

- 实际入口为 `src/main/java/com/vay/camerasnap/HookEntry.java`，使用 libxposed API 102 的生命周期和 interceptor-chain Hook（beta11 起迁移包名）。
- 1.7 源码移至 `app/src/legacy/` 保存，不参与编译；移除实际构建中的 YukiHookAPI、KSP、legacy API 82 和 DexKit 依赖。
- 系统按键线程只检查缓存模式并转交任务。Provider 读取、长按定时和服务 IPC 在独立 policy worker 上执行。仅消费由模块接管的音量下按键序列，避免配置加载途中吞掉原系统的松键事件。
- 默认关闭；必须由用户在设置中选择模式并授予权限。模块自身的 ConfigProvider 只向模块、系统 UID 和系统相机 UID 提供配置读取，不开放配置写入或文件访问。
- 新 SnapService 实际声明在模块 Manifest 中，只允许持有系统权限 `STATUS_BAR_SERVICE` 的跨 UID 调用方启动；拍摄由 system_server 启动，不向不存在的相机组件广播，也没有安装全局权限绕过 Hook。
- 新 CaptureEngine 使用 Camera2 + MediaStore，图片保存使用 IS_PENDING 提交，视频过短或录制失败时删除无效条目。停止时等待已经取出的 JPEG 写入完成后再结束前台服务与释放 WakeLock。
- 相机设置注入使用生命周期和标准 Preference，标题直接提供文本，提供模块桌面设置作为稳定入口。

目前限制：主用户、标准 Camera2 画质、连拍最多 100 张、沿用原模块单次录像最长 1 小时。未移植小米私有图像处理。由于尚未提供目标系统版本/框架且没有连接设备，系统按键和后台相机权限链路仍待真机验收。

验证：Debug/Release 构建、Release R8、六项长按状态机测试、Android Lint（0 错误，9 警告）、APK 内现代注册文件及组件类型检查、Debug APK v2 签名检查。项目自带 Gradle 8.6 wrapper 构建已通过。Lint 的 libxposed 自定义规则要求更新的 Lint 版本，未执行；另做了入口/作用域/类定义检查。测试范围没有包含真机拍摄。

可安装产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta1(2000).apk`。Release 默认未签名，不能把它当作可直接安装的交付包。

## 振动反馈修复：2.0-beta2

用户反馈 beta1 拍摄时缺少旧版振动。对照旧相机 `SnapTrigger.onDone()` 及原模块录像 Hook，确认照片完成后和录像启动时有短振反馈；beta1 的独立 CaptureEngine 遗漏了这一行为，并且未声明 VIBRATE 权限。

beta2 添加 VIBRATE 权限，在每张照片成功提交 MediaStore、拍摄尚未结束时，以及 MediaRecorder 成功开始录像后，执行旧版相同的 `[10, 20]` 非循环振动波形（延迟 10 毫秒、振动 20 毫秒），使用相同的 ALARM AudioAttributes。没有振动器或振动调用异常时，拍摄流程继续，不将其作为拍摄失败。通知渠道仍保持无振动，避免额外反馈。

版本提升为 2.0-beta2 / 2001，便于覆盖安装。Debug/Release 构建、六项现有按键测试和 Lint 通过，Debug APK 签名验证通过，打包 Manifest 已包含 VIBRATE。振动实际效果仍需目标手机验证。

当前可安装产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta2(2001).apk`。

## 原生街拍设置入口替换：2.0-beta3

用户指出新版相机实际有街拍设置，点击会跳到系统极速相机设置。补充反编译 `CameraCapturePreferenceFragment` 后确认：`Gb()` 将 `pref_street_shot` 加入 `category_street_shot_setting`；`vf()` 注册点击监听；`xh(String)` 对该键启动 `com.android.settings.SubSettings`，指定 `AodAndLockScreenSettings` 和 `volume_down_launch_camera_or_take_photo`。

使用 API 102 单独 Hook `CameraCapturePreferenceFragment.xh(String)`：只在 `pref_street_shot` 分支成功打开模块 SettingsActivity 时消费点击；其他键或模块打开失败继续原逻辑。这条 Hook 不依赖新增设置项的成功与否。

入口绑定补充覆盖 CameraCapturePreferenceFragment，并在 `BasePreferenceFragment.ue()` 重建列表后和 CameraPreferenceFragment.onResume 后执行；优先复用原生街拍项，保留标题、替换摘要和点击监听。旧实现只覆盖普通/通用设置页面，未处理照片设置页面和列表重建。模块桌面入口继续可用。

版本为 2.0-beta3 / 2002；可安装 APK 为 `app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta3(2002).apk`。原生点击 Hook 与界面跳转仍需真机验收。

## 桌面图标隐藏开关：2.0-beta4

SettingsActivity 新增“隐藏桌面图标”开关。将 MAIN/LAUNCHER intent-filter 从设置 Activity 移到默认启用的 LauncherActivity activity-alias；只切换别名的启用状态，使用 DONT_KILL_APP，设置 Activity 保持可被相机明确启动。开关状态读取 PackageManager，随系统保存，恢复页面时同步；调用失败时提示并回滚开关显示到实际状态。

版本为 2.0-beta4 / 2003；Debug/Release 构建、Lint 和 Debug 签名检查通过，打包 Manifest 的 alias 指向 SettingsActivity。可安装产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta4(2003).apk`。桌面隐藏/恢复及隐藏后的相机入口需真机确认。

## Miuix 界面重构：2.0-beta5

设置页面由原生 Java View 改为 Kotlin ComponentActivity + Jetpack Compose，使用用户指定的 Miuix 最新稳定版 0.9.4（miuix-ui-android、miuix-preference-android）。界面采用 Miuix 大标题、分组卡片、单选指示、SwitchPreference 和按钮，主题跟随系统。保留关闭/连拍/录像、权限申请、停止拍摄、状态、摄像头与图标开关。摄像头展开选择后立即保存；页面处于前台时监听 SharedPreferences 变化更新结果；权限被撤销时继续自动关闭拍摄模式。配置文件、Activity 名称和桌面别名不变，已有配置无需迁移。

用户要求使用最新 Miuix 后，构建环境升级为 Gradle 9.6.0、AGP 9.4.1、Kotlin/Compose compiler 2.4.20 和 compileSdk 37。AGP 使用既有 DSL/外部 Kotlin 兼容配置；资源 ID 改为 non-final 以支持新 R8 资源压缩。依赖的原生图形库保留所提供的 ABI，增加模拟器 x86/x86_64 支持。最终 APK 验证 minSdk 仍为 29、targetSdk 仍为 34；本次没有修改 API 102 Hook、系统按键或拍摄引擎逻辑。

验证：项目 wrapper 的 Debug/Release/R8 构建通过；6 项按键测试通过；Lint 0 错误、12 警告；Debug v2 签名验证通过。Android 16 / API 36 模拟器安装最终 Debug APK，检查浅色与深色界面、权限弹窗与模式选择、摄像头立即保存和重启恢复、桌面图标隐藏/恢复，以及图标隐藏后直接启动 SettingsActivity。没有 AndroidRuntime 崩溃记录。截图和 UI 树在 `build/miuix-analysis/`。

最终可安装产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta5(2004).apk`，SHA256 `d0d74c9a7947ed990f63e6685993fd906b0e68a8334dab922a2c63e0c5ea171e`。模拟器没有 LSPosed 和目标小米系统，因此实际息屏拍摄、原生相机入口、旧 Android 版本兼容性仍需目标设备验证，不能以界面检查代替。

## 拍照反馈时机与弱振修复：2.0-beta6

用户反馈息屏拍照似乎不振、录像振动微弱。代码确认 beta5 存在漏振场景：JPEG 在 IO 线程写入并提交相册后才回主线程调用振动，且振动受 `!closed` 条件约束；如果用户在保存期间松键，close() 提前设置 closed=true，照片仍完成保存却不会振动。录像在 MediaRecorder.start() 成功后立即请求振动，所以没有这段保存竞态。两个模式使用 `[10,20]` 默认振幅波形，20 毫秒可能在部分硬件上不明显；无真机日志，不能断言这是该手机全部症状的唯一原因。

beta6 将照片反馈移到 JPEG 已生成并取出、尚未开始写盘的位置，一帧只请求一次，曝光完成后反馈，不受随后松键或保存耗时影响。保存失败仍通过状态单独报告，振动表示照片生成而非相册保存成功。录像继续在成功开始录制后反馈。统一改用 60 毫秒 one-shot；支持振幅控制时请求 255，否则使用 DEFAULT_AMPLITUDE。Android 12 起通过 VibratorManager 获取默认振动器；Android 13 起使用 VibrationAttributes，旧系统继续 AudioAttributes，保留 ALARM 用途，不绕过系统振动/勿扰设置。

日志标签 UnlockMIUICameraSnap，记录 `Capture vibration requested: photo_ready` / `video_started`、振动时长与振幅，以及无振动器或调用异常。请求日志仅证明发起 API 调用，不证明系统执行了马达振动。没有增加自动重试，以免产生重复反馈。

Debug/Release 构建、6 项既有按键测试、Lint 和 Debug 签名检查通过；物理振感与目标设备息屏策略尚待真机验证。可安装产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta6(2005).apk`。

## 保存成功与录像起止反馈：2.0-beta7

按用户最新要求，照片改为每张成功保存后振动一次；录像改为开始和结束各振动一次，期间没有周期振动。反馈继续使用 beta6 的 60 毫秒单次振动及振幅设置。

照片反馈移到写入 JPEG 并成功 publish 到 MediaStore 后的主线程回调，日志事件为 `photo_saved`。振动位于 `!closed` 条件之外，因此松键前已接收的照片即使在停止后才保存完成，仍各自反馈一次；保存失败不振动。前台服务等待已排队的照片写入完成再退出，保留最后一张的反馈机会。

录像成功 start 后记录 recording 并请求 `video_started`；close 在停止和释放录制器后，仅对已成功开始的录像请求一次 `video_stopped`。closed 防止重复关闭产生重复反馈，启动失败不会发出结束反馈。结束振动表示录制停止，视频是否保存成功仍以模块状态为准；保存失败也会给出停止反馈。

设置页与 README 同步更新反馈说明，版本为 2.0-beta7 / 2006。实际息屏振感仍需目标手机验证。

Debug/Release 构建通过，6 项现有按键回归测试通过，Lint 为 0 错误、12 警告，Debug APK 的 v2 签名验证通过。安装产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta7(2006).apk`，SHA-256：`D628712C0DA4EA3BD6AB46BDE26A8BFF27B6678F743B1444C38E6F521BBF494C`。

## MIUI 拒绝 ALARM 振动：2.0-beta8

连接手机已安装 beta7 / 2006，Android 15。用户确认连拍每张保存、录像松键后保存均正常。ADB 日志中录像 `video_started` 和 `video_stopped` 均已请求；VibratorManagerService 将模块 uid=10295 的 ALARM 请求直接结束为 `IGNORED_RINGTONE_OR_NOTIFY_MIUI`。连拍期间同样连续出现拒绝记录。dumpsys 中硬件支持振幅控制，ALARM 与 HARDWARE_FEEDBACK 强度均为 MEDIUM，但 TOUCH 为 OFF。因此前几版仅改变回调时机和振幅没有解决实际系统拒绝，不能把开始时感到的一次振动认定为模块请求成功执行。

beta8 在 Android 13 及以上改用 USAGE_HARDWARE_FEEDBACK，匹配物理按键触发的拍摄反馈；不使用绕过系统设置的标志。AOSP Android 15 的 VibrationSettings 将 HARDWARE_FEEDBACK 列入后台和省电模式允许类型，因此没有改用普通 TOUCH。Android 10–12 的旧 AudioAttributes 分支仍保留 ALARM，未声称验证这些系统。

vibrate 调用为异步提交，记录最后反馈的 uptime+160ms（60ms 振动及 100ms 余量）。close 等待所有照片写入及回调完成后，根据剩余反馈时间延迟服务完成回调，保留前台服务与 WakeLock，减少结束短振被服务退出截断的可能。录像期间没有额外振动或重试。日志新增 usage 信息，便于验证新类型是否被系统接受。

原始诊断文件位于本地忽略目录 `build/miuix-analysis/device-haptics-log.txt` 和 `device-vibrator.txt`；包含手机系统日志，不纳入项目源码。新类型在目标手机的实际执行结果仍需复测。

Debug/Release 构建、6 项现有回归测试与 Debug v2 签名验证通过，Lint 0 错误、12 警告。产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta8(2007).apk`。

## 减轻反馈：2.0-beta9

用户确认 beta8 已实现逐张照片保存成功及录像起止振动，但振感偏强。beta9 将统一反馈振幅从 255 降至 128，时长从 60ms 缩短到 40ms；不支持振幅控制的设备仍使用默认振幅，缩短时长。反馈时间保护由同一时长常量加 100ms 余量计算。HARDWARE_FEEDBACK 类型、每张保存成功反馈和录像起止反馈保持不变，录制期间没有振动。请求振幅变化不等同于物理振感按比例变化，实际强度需用户确认。

Debug/Release 构建、6 项现有回归测试与 Debug v2 签名验证通过，Lint 0 错误、12 警告。产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta9(2008).apk`。

## 恢复旧模块振动参数：2.0-beta10

按用户要求，对照 legacy SnapTrigger.vibratorShort() 的 longArrayOf(10,20)，恢复 createWaveform([10,20],-1)：延迟 10ms、振动 20ms、默认振幅且不循环。不再显式指定 128 或 255。服务退出保护包含完整 30ms 波形与 100ms 余量。保留 beta8 已由用户确认生效的 HARDWARE_FEEDBACK 类型、逐张照片保存成功反馈和录像起止反馈。由于用途类型及硬件、系统缩放可能不同，参数匹配旧模块不代表物理振感完全相同。

Debug/Release 构建、6 项现有回归测试与 Debug v2 签名验证通过，Lint 0 错误、12 警告。产物：`app/build/outputs/apk/debug/UnlockMIUICameraSnap_2.0-beta10(2009).apk`。

## 项目与包名迁移：2.0-beta11

项目、应用显示名称、设置页署名、日志标签和 APK 前缀统一改为 UnlockCameraSnap，包名改为 com.vay.camerasnap。同步迁移 namespace/applicationId、主代码、测试和 legacy 源码目录与包声明、Provider authority、配置及启动/停止 Action、API 102 java_init.list 和 R8 保留规则。历史构建产物及旧手机日志名称保留原记录。仓库所在目录未移动。

新包名是独立应用，旧包不能通过覆盖安装迁移设置与权限。用户需在框架中停用旧模块、启用新模块的相机/system 作用域并重启，再授予权限、选择拍摄模式。拍摄和振动行为沿用 beta10。

Debug/Release 构建、迁移包名后的 6 项回归测试和 Debug v2 签名验证通过，Lint 0 错误、12 警告。打包 Manifest 验证包名 com.vay.camerasnap 与应用名称 UnlockCameraSnap，Debug/Release 的 java_init.list 均为 com.vay.camerasnap.HookEntry。产物：`app/build/outputs/apk/debug/UnlockCameraSnap_2.0-beta11(2010).apk`。

## 自定义保存路径：2.0-beta12

设置页的固定保存位置改为 Miuix 可展开编辑项，包含路径输入、保存、取消和恢复默认。支持共享存储 DCIM 下的目录，默认 DCIM/Camera/Snap，照片和视频共用路径。使用 MediaStore RELATIVE_PATH，无需全盘文件权限。保存后写入 snap_config_v2 的 save_path，并同步配置 Bundle；CaptureEngine 使用启动时的配置快照，因此当前拍摄不切换目录，下次拍摄生效。已有媒体不迁移，状态提示显示实际配置路径。

MediaSavePath 验证根目录、层级、控制字符、隐藏目录和目录名 UTF-8 长度，拒绝绝对路径及目录穿越；旧配置缺失或无效时回退默认目录。添加 6 项路径验证回归测试，保留既有按键测试。

Debug/Release 构建、12 项回归测试及 Debug v2 签名验证通过，Lint 0 错误、13 警告。新目录的真机拍摄保存待用户验证。产物：`app/build/outputs/apk/debug/UnlockCameraSnap_2.0-beta12(2011).apk`。

## DCIM 外的本地文件夹：2.0-beta13

增加 OpenDocumentTree 系统选择器，使用 EXTRA_LOCAL_ONLY 请求本地文件夹。选中后验证目录 MIME 与 FLAG_DIR_SUPPORTS_CREATE，取得持久读写授权，再保存 save_tree 和 save_tree_name。取消或授权失败保持原配置；恢复默认或使用 DCIM 相对目录清除树目录配置。先前授权保留，避免切换目录后中断尚在写入旧目录的拍摄。

CaptureEngine 在创建文件前检查所选目录的持久写入授权，通过 DocumentsContract.createDocument 创建 JPEG/MP4，继续使用 OutputStream 和 MediaRecorder 的 ParcelFileDescriptor 写入。SAF 分支不发送 MediaStore.IS_PENDING，保存完成以关闭流/描述符为界，失败通过 deleteDocument 尝试清理。录像启动前验证文件描述符可 seek，不兼容的提供程序明确提示改选手机本地文件夹。配置仍在每次拍摄开始时快照化，反馈与停止逻辑保持不变。

系统禁止选择的目录不尝试绕过，不新增全盘文件访问权限。所选目录可能不自动显示在相册，用户可通过文件管理器查看。依据：[Android SAF 文档](https://developer.android.com/training/data-storage/shared/documents-files)。

Debug/Release 构建、12 项既有回归测试和 Debug v2 签名验证通过，Lint 0 错误、14 警告。系统选择器及新目录照片/视频保存、授权跨重启保留尚需手机验收，单元测试未覆盖真实文档提供程序。产物：`app/build/outputs/apk/debug/UnlockCameraSnap_2.0-beta13(2012).apk`。
