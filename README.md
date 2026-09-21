# ReOutlook / ReBrowser

一个 Android 应用，两种互相隔离但可随时切换的使用形态：

- **ReOutlook**：在线使用完整 Outlook Web，并将已同步邮件保存到本地供离线阅读。
- **ReBrowser**：基于系统 WebView Multi-Profile 的轻量浏览器；以“总标签页”管理独立网站环境，以“子标签页”管理环境内页面。

安装同一个 `io.github.reoutlook` APK 后，桌面会出现 **ReOutlook** 和 **ReBrowser** 两个入口。它们不是两个 APK；ReOutlook 独占 WebView Default Profile，ReBrowser 只使用隔离的命名 Profile。

> **项目状态：实验性原型。** 当前代码已经在真实设备上完成核心流程验证，但尚未接受独立安全审计，也不是成熟的生产邮件或浏览器产品。

## ReOutlook

ReOutlook 保留 Outlook 官方网页、学校登录和 MFA 流程，同时提供本地离线邮件档案：

- 在受限制的 Outlook 来源内观察结构化邮件响应；
- 主动、限速地回填历史会话和完整正文；
- 使用远端修订标识和保守检查点避免错误完成同步；
- 按账号隔离邮件、去重索引和同步状态；
- 在禁用脚本、网络和文件访问的原生离线界面中阅读正文；
- 使用全屏网页和可拖拽、自动吸附的悬浮球抽屉；
- 提供必须由管理员签名及机主锁屏共同授权的加密应急导出。

认证请求只在 Outlook 页面内存中复用。原生消息桥和邮件数据库不读取或持久化密码、Cookie、访问令牌、刷新令牌或 Web Storage。

详细设计见 [ReOutlook 架构与同步](docs/REOUTLOOK_ARCHITECTURE.md)。

## ReBrowser

ReBrowser 的核心原则是 **Persist by intent, not by visitation（由意图决定持久化，而不是由访问决定）**：

- 每个总标签页拥有独立命名 Profile，并可包含多个共享该 Profile 的子标签页；
- 新总标签页默认是关闭即清理的临时环境；
- 用户主动上锁后成为副总标签页，完整环境进入书签栏并可收起、恢复；
- 再次明确提升后成为不可关闭、启动时恢复的主总标签页；
- 收藏夹以单行网址列表保存标题和网址，书签栏保存完整副总标签页环境；
- ReBrowser 独占的下载管理支持命名 Profile 登录态、进度、取消、重试、删除、SHA-256，以及受限 Blob/Data 重建；
- 同一“Profile＋顶层 origin”五分钟内第三次下载起进入有界待确认队列，危险类型在保存和外部打开前警告；
- 最多同时存在 5 个主总标签页、64 个总标签页，每个总标签页最多 50 个子标签页；
- 支持网页新窗口转为当前环境内的子标签页、原生全屏视频、持久化全局方向及独立的视频方向策略；
- 不支持 WebView `MULTI_PROFILE` 时完全禁用，不回退到 ReOutlook 的 Default Profile。

当前版本不会把自身注册为系统默认浏览器候选。完整模型和交互说明见 [ReBrowser 设计](docs/REBROWSER_EXPLORATION.md)。独立的 [ReBrowser 仓库](https://github.com/13513208952/ReBrowser) 仅作为项目入口，代码统一维护在本仓库。

## 管理员控制

项目保留两种不出现在普通界面中的 ADB 管理能力：

- **ReOutlook 维护**：邮件导出必须同时通过离线管理员签名和机主系统锁屏确认，导出文件使用 AES-256-GCM 与 RSA-OAEP 加密。
- **ReBrowser 控制**：版本化协议支持按总标签页／子标签页 ID 执行导航、生命周期、设置、诊断、审计和有界修复；永久管理员根可独立授权全部三级操作，机主锁屏单独授权仅限一、二级。

ReBrowser 管理协议还提供有界的下载策略、任务、批准、拒绝、取消、重试、删除、清理和修复命令，但不提供打开、安装或执行文件的命令。两种接口都不提供任意 JavaScript、Cookie、Token、Web Storage 或密码提取能力。参见 [管理员工具](tools/admin/README.md) 和 [ReBrowser 管理协议](docs/REBROWSER_ADMIN_CONTROL.md)。管理员私钥不包含在 APK 或公开仓库中。

## 下载与安装

开发者预览 APK 发布在 [GitHub Releases](https://github.com/13513208952/ReOutlook/releases)。当前版本要求 Android 10／API 29 或更高版本；Android 8/9 留待未来完整浏览器内核路线重新评估。当前公开包使用 Android Debug 证书签署，以便测试设备连续升级，但 Release 构建本身不含 `DEBUGGABLE` 标志。不要把该签名视为正式生产签名。

也可以从源码构建。要求 JDK 17 和 Android SDK 36：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [ReOutlook 架构与同步](docs/REOUTLOOK_ARCHITECTURE.md) | 邮件采集、回填、检查点、账号隔离和离线界面 |
| [ReBrowser 设计](docs/REBROWSER_EXPLORATION.md) | 双层标签、Profile、生命周期、方向和返回策略 |
| [ReBrowser 管理协议](docs/REBROWSER_ADMIN_CONTROL.md) | ADB 挑战授权、命令范围和数据边界 |
| [项目状态](docs/PROJECT_STATUS.md) | 已验证能力、当前限制和后续工作 |
| [隐私说明](PRIVACY.md) | 本地数据、网络通信、备份和删除 |
| [安全策略](SECURITY.md) | 安全边界、管理员接口和漏洞报告 |
| [管理员工具](tools/admin/README.md) | 命令行使用和加密导出解密 |

## 数据与安全边界

- 数据库使用应用私有 SQLite、Android 文件级加密（FBE）和应用沙箱，不额外使用 SQLCipher；
- Android 自动备份保持关闭，备份和导出必须经过显式授权；
- ReBrowser 网站状态由系统 WebView 存放在各命名 Profile 中；
- 应用没有开发者服务器、广告、遥测或分析服务；
- 普通界面允许截图，涉及管理员授权和导出的维护界面使用 `FLAG_SECURE`。

更完整的边界以 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md) 为准。

## 许可证与声明

本项目采用 [GNU General Public License v3.0](LICENSE) 发布，与 Microsoft、Outlook、Google Chrome 或任何学校没有隶属或官方合作关系。Outlook、Chrome 及相关名称和商标属于其各自权利人。
