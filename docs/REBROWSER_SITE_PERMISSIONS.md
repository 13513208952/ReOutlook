# ReBrowser 网站权限模型

ReBrowser 的网站权限以命名 Profile 和 HTTPS 顶层来源为边界。全局启用只表示网站可以申请，不会直接把 Android 权限授予所有网站。

## 权限矩阵

| 能力 | 默认值 | 单站授权窗口 |
| --- | --- | --- |
| 剪贴板文本读写 | 开启 | 5 分钟或 15 天 |
| 模糊定位 | 开启 | 5 分钟或 15 天 |
| 麦克风 | 关闭 | 单次或 3 小时 |
| 摄像头 | 关闭 | 单次或 3 小时 |
| 高精度定位 | 关闭 | 单次或 3 小时 |
| 网站通知 | 永久禁止 | 无 |
| 后台推送 | 永久禁止 | 无 |
| 网页运动传感器 | 永久禁止 | 无 |
| 应用后台运行 | 开启 | 应用级策略，不是网站授权 |

上传继续使用 Android 系统文件选择器；下载继续使用独立的确认、频率、风险和命名 Profile Cookie 规则，不纳入网站权限开关。

## 授权边界

单站授权键为：

```text
ReBrowser 命名 Profile + HTTPS 顶层 origin + 权限类型
```

第一版只接受当前可见顶层页面的申请。跨来源 iframe、HTTP、opaque origin、隐藏标签、后台页面、已导航页面和已销毁 Profile 的请求直接拒绝且不显示提示。

单次授权只覆盖当前 API 请求或媒体流；页面导航、标签隐藏、进入后台或关闭页面后失效。3 小时、5 分钟和 15 天窗口从批准时开始计算，不因使用续期；窗口可以跨进程恢复，但后台期间不能调用权限。关闭全局能力会删除已有授权，重新开启后不能恢复。

用户拒绝后，同一页面的同类请求进入 30 秒静默拒绝窗口，避免提示轰炸。权限提示在浏览器固定界面中显示规范化 origin；返回、切换标签、导航或 30 秒无操作均视为拒绝。

## Android 权限与 ReOutlook

`CAMERA`、`RECORD_AUDIO`、`ACCESS_COARSE_LOCATION` 和 `ACCESS_FINE_LOCATION` 属于同一 APK。ReBrowser 只有在网站请求已经通过自身提示后才请求 Android 运行时权限。`MODIFY_AUDIO_SETTINGS` 是 WebView/WebRTC 配置输入设备所需的普通权限，不会单独弹出系统授权提示，也不授予录音能力。ReOutlook 的 WebChromeClient 无条件拒绝媒体和定位请求，并关闭原生 WebView 定位通道。

蓝牙耳机在麦克风工作期间可能由高音质 A2DP 切换到支持双向音频的 HFP/HSP/SCO，导致其他应用的播放暂时呈现通话音质；媒体流停止后由系统恢复。这是 Android 音频路由行为，不表示其他应用的音频被网站读取。

管理员协议可以查询全局策略以及只含 Profile、HTTPS origin、权限类型和期限的有效授权元数据，可以单向禁用能力，并可清除全部或某一权限类型的授权。状态校验报告过期、损坏、未来时间、已禁用、重复和超限授权的有界计数，三级修复会删除这些无效记录。管理员不能开启能力、创建网站授权、代替用户批准，也不能取得媒体、读取剪贴板或得到坐标。

## 定位

ReBrowser 禁用 WebView 原生 Geolocation，使用固定语义桥实现 `getCurrentPosition` 和前台轮询式 `watchPosition`。高精度请求要求独立的高精度授权。模糊定位会在原生层量化经纬度、把报告精度降低到至少约 2 公里，并删除高度、方向和速度。

进入后台、导航或页面失效后，新的定位结果不会返回。关闭高精度权限不会把现有高精度监听静默降级；页面需要重新申请模糊位置。

## 剪贴板

受管理范围是 `navigator.clipboard.readText()` 和 `writeText()`，仅支持文本且每次仍要求当前用户手势。富内容 `read()`／`write()`不支持。用户通过系统文本菜单手动复制和粘贴不属于网站程序化权限。

Android WebView 没有公开的原生剪贴板 Content Setting。ReBrowser 使用文档启动时安装的固定 API 策略，并在权限关闭或授权撤销后重建页面，避免页面继续持有旧 API 引用。这不是完整 Chromium 内核级隔离；未来如要求更强保证，需要评估完整浏览器内核。

## 永久禁用能力

ReBrowser 不声明 `POST_NOTIFICATIONS`，不实现通知或 Push 原生桥，并在页面环境中隐藏 Notification、Push 和 ServiceWorker 通知入口。运动与方向传感器构造器和事件入口在网页脚本运行前被阻断。浏览器自身仍可依据 Android Activity 配置变化实现界面和视频自动旋转，不向网页提供原始传感器数据。

## 后台策略

进入后台后，普通 WebView 会暂停并静音，网站权限请求直接失败，摄像头和麦克风页面运行时被销毁。授权期限继续流逝。

关闭“允许后台运行”后，Activity 不可见时进一步销毁网页运行时：

- Android DownloadManager 的 HTTP(S) 下载可继续；
- 已进入纯原生写入阶段的任务可以完成；
- 依赖来源页面的 Blob 下载以 `background-runtime-disabled` 失败；
- 应用不注册后台 Service、Job、Alarm 或开机自启任务。

Android 可能保留不执行任务的缓存进程；这不代表应用正在后台工作。

## WebView 要求

除 `MULTI_PROFILE` 外，ReBrowser 还要求 `WEB_MESSAGE_LISTENER` 和 `DOCUMENT_START_SCRIPT`。缺少任一能力时 ReBrowser 完全禁用，不回退 Default Profile，也不在无法安装权限策略的情况下继续浏览。
