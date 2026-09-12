# ReOutlook

一个用于验证以下思路的 Android 原型：

- 在线时在 WebView 中使用 Outlook 官方网页及学校自己的认证流程；
- 保留 WebView Cookie、DOM Storage 和站点数据，尽量延续登录会话；
- 仅在 Outlook 页面上尝试识别已经打开的邮件并保存到本地；
- 网络或登录不可用时，通过独立的本地界面阅读缓存邮件。

## 当前阶段

这是 **0.1 验证原型**，不是可发布的邮件客户端。目前能够：

1. 打开 `https://outlook.office.com/mail/`；
2. 支持网页后退、刷新、文件选择和学校登录跳转；应用控制收纳在悬浮球抽屉中；
3. 在页面启动前安装受域名限制的响应观察器，自动缓存 Outlook 已加载的结构化完整邮件；
4. 根据 `FindConversation` 结果限速调用 `GetConversationItems`，从新到旧主动回填；
5. 使用带远端修订标识的本地会话检查点：未变化会话跳过，新回复或 ChangeKey 变化后重新同步；每次启动最多新增回填 50 个会话；
6. 手动运行实验性 DOM 适配器，作为当前阅读邮件的回退采集方式；
7. 在“本地邮件”中离线显示已缓存正文，列表按收件时间排序并按需读取正文；
8. WebView 全屏显示，本地邮件采用紧凑标题栏、彩色头像和分层排版；
9. 使用可拖拽、半透明、记忆高度并自动吸附左右边缘的悬浮球打开抽屉；
10. 返回键优先返回邮件详情或外部网页的上级页面，仅在邮箱列表顶层采用两次返回退出；
11. 适配状态栏、挖孔/刘海、底部手势安全区，并为贴边悬浮球设置局部手势排除区；
12. 可持久化开启 WebView 邮件列表自动滚动，抽屉打开、邮件详情页或用户触摸页面时自动暂停；
13. 根据 Outlook anchor mailbox 建立账号指纹，邮件、去重索引和同步检查点全部按账号隔离；
14. 内置无普通界面入口、仅持 Android shell 权限可调用的管理员维护通道，必须同时通过离线管理员签名与机主系统锁屏验证，才能生成管理员公钥加密的账号导出。

学校登录、Duo、移动版 Outlook、自动分页、结构化正文采集和离线正文均已在真实设备上验证。v2 → v4 账号隔离迁移已在保留 612 封邮件的真实数据库上通过，PUID/SMTP 同账号别名已合并；会话修订变化后会重新同步；ADB 管理员签名、机主锁屏确认、加密导出和离线解密也已在主力机完成端到端验证。

回填请求只在 Outlook 页面中复用其临时请求上下文；认证信息不会通过原生桥传递，也不会写入应用数据库。

目前尚未实现附件及远程图片缓存、多账号界面、后台同步和离线操作回放。数据库位于 Android 凭据加密的应用私有目录，但没有额外采用 SQLCipher 整库加密；这是当前选定的“普通官方客户端式”安全模型。

## 安全边界

- 原型不会读取或保存 Cookie、访问令牌、密码及 Web Storage 内容；
- DOM 和结构化响应采集仅在 Outlook 官方邮件域名上执行；
- 本地正文 WebView 禁用 JavaScript、网络请求、文件访问和页面跳转；
- SQLite 文件依赖 Android 文件级加密（FBE）和应用沙箱保护，数据库被解锁并单独提取后的内容没有 SQLCipher 二次保护；
- 系统自动备份保持关闭，后续备份只通过带账号验证或机主授权的应用内显式流程执行；
- 管理员私钥不进入 APK 或项目仓库，维护组件常驻注册但仅限 Android shell 调用，且没有正常界面入口。

## 构建

要求 JDK 17 和 Android SDK 36：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```

安装包将生成在 `app/build/outputs/apk/debug/app-debug.apk`。

## 下一步验证

1. 用不同学校认证流程测试登录、MFA、文件选择和会话恢复；
2. 通过实际 Outlook 页面调整 `CaptureScript.java` 中的 DOM 适配器；
3. 记录邮件列表和阅读窗的稳定属性，完成“从新到旧”采集；
4. 在第二个真实 Outlook 账号上验证账号切换与分区隔离；
5. 将数据访问层迁移到 Room，再测试附件和内嵌图片缓存。

## 隐私与安全

参见 [PRIVACY.md](PRIVACY.md)、[SECURITY.md](SECURITY.md) 和管理员工具说明 [tools/admin/README.md](tools/admin/README.md)。管理员私钥不包含在公开仓库中。

## 许可证与声明

本项目采用 [GNU General Public License v3.0](LICENSE) 发布，与 Microsoft、Outlook 或任何学校没有隶属或官方合作关系。Outlook 及相关名称属于其各自权利人。