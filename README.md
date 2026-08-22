# 旧版互传（LegacySend）

一个独立实现的原生 Android Java 局域网文件传输应用，目标是兼容 LocalSend Protocol v2.2 的设备发现和 Upload API。项目不引用、导入或构建相邻的 LocalSend 源码目录。

当前版本：`1.2`（versionCode 3）。

## 下载与安装

已编译好的 APK 文件可以直接在本项目的 **GitHub Releases（发布版）** 页面下载安装，无需手动编译。针对 Android 2.3+ 和 Android 6 设备，应用均支持使用 v1 签名直接安装。

## 源码构建（可选）

如需自行从源码构建，要求环境如下：

* JDK 17
* Gradle Wrapper 8.9
* Android Gradle Plugin 8.7.3
* Android SDK Platform 34
* Android SDK Build Tools 34.0.0

构建与验证命令：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug

```

编译输出 APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk

```

项目 `minSdkVersion` 为 9，`targetSdkVersion` 为 28。目标 SDK 维持在 28，是为了让旧版 Android 系统使用统一且稳定的公共目录与存储权限语义；本项目不面向 Google Play 发布。

## 功能

* UDP 组播设备发现、公告和回包
* HTTP/HTTPS LocalSend v2 (v2.2) 注册接口
* 支持分块传输编码（Chunked Transfer Encoding）
* 单文件和多文件选择、发送和接收
* 接收前接受/拒绝
* 文件级令牌、会话 ID、来源 IP 和证书指纹检查
* 流式上传和保存，不将完整文件载入内存
* 总体进度、失败提示和双方取消
* 同名文件自动添加 `(1)`、`(2)` 后缀
* 中文、空格和常见特殊字符文件名
* 前台接收服务，Activity 重建不终止当前传输
* 所有可见文案均为简体中文

## 主要目录

```text
app/src/main/java/com/blithe/legacysend/
├── LegacySendApp.java       应用级状态、后台任务和 UI 事件
├── ReceiveService.java      前台保活接收服务
├── discovery/               UDP 组播发现
├── model/                   设备和文件模型
├── protocol/                LocalSend JSON 格式 (v2.2)
├── security/                自签名身份、BKS KeyStore、mTLS、证书指纹固定、SimpleX509Generator
├── server/                  HTTP/HTTPS 注册、准备、上传、取消接口
├── storage/                 SAF 与低版本文件系统保存目录及重名处理
├── transfer/                HTTPS/HTTP 发送客户端和进度/取消
├── ui/                      传统 Android View 中文界面
└── util/                    流式复制和进度工具

```

业务源码全部为 Java；没有 Kotlin、Compose、Flutter、Dart 或 React Native 代码。Gradle 使用 Groovy DSL。

## Android 2.3–4.4 兼容性处理

* **minSdk 9 (Android 2.3 Gingerbread) 支持：** 针对 API 9–17 无 `AndroidKeyStore` 且 OpenSSL / Conscrypt 解析 ASN.1/DER 结构极度严格的问题，实现了自定义 `SimpleX509Generator`。
* **BKS KeyStore 格式：** 在 API 9–17 上使用 `BouncyCastle` (`BKS`) 存储私钥与证书，解决 Android 2.3 上 `PKCS12` 序列化崩溃问题。
* **ASN.1 / DER 手动编码修复：** 补全 `Subject` / `Issuer` 中 `AttributeTypeAndValue` 的 `SEQUENCE` 包装，并强制将 `commonName` (2.5.4.3) 的字符串 Type 指定为 `PrintableString` (0x13)，彻底修复低版本 OpenSSL 的 `ASN.1 encoding routines:OPENSSL_internal:WRONG_TAG` 崩溃。
* **算法与 Y2K38 溢出处理：** API 9–17 自签名证书采用 `SHA1withRSA`，有效期限制为 10 年，防止 32-bit 时间戳溢出。
* **SAF 与低版本文件系统：** API 19+ 使用 `ACTION_OPEN_DOCUMENT` 和 SAF；API 9–18 使用应用内内置文件选择器直接读取外部存储。
* **Android 4.4.2 (API 19–20) TLS 接收兼容：** Kindle Android 4.4.2 的 TLS 1.2 服务端仅支持 CBC 密码套件，与 LocalSend 1.17.0 的 Rust TLS 客户端无公共套件。API 19–20 接收服务使用 LocalSend 官方允许的 `protocol: "http"` 模式；API 9–18 及 API 21+ 接收服务与所有对外发送保持 HTTPS。
* 文件保存路径：API 9–28 保存到公共 `Download/LegacySend`；API 29+ 保存到 `Android/data/com.blithe.legacysend/files/Download/LegacySend`。
* 获取 `MulticastLock` 后监听组播；网络和文件 I/O 全部在后台线程执行。
* 文件使用 32 KiB 缓冲流式复制，支持 Chunked 流式接收与落盘，并校验实际字节数与元数据大小一致。
* 前台 Service 为 API 9–25 使用传统通知，为 API 26+ 创建通知频道。

## 依赖

运行时没有第三方依赖，仅使用 Android SDK、Java 标准库和 `org.json`（Android 系统自带）。因此运行依赖不存在额外的低版本 API 兼容风险。

测试依赖：

* JUnit 4.13.2：仅在主机 JVM 运行测试，不打入 APK。
* `org.json:json:20240303`：仅为主机 JVM 测试提供与 Android `org.json` 对应的实现，不打入 APK。

## 当前验证状态

### 已实现并经过测试

* 主机单元测试 12 项：协议序列化、多文件元数据、中文/特殊字符、接受/拒绝/取消/超时、重名、进度、流式复制、中断检测和内容哈希一致性。
* Gradle 编译、Lint 和 debug APK 打包。
* APK 清单检查确认 `minSdkVersion=9`、`targetSdkVersion=28`。
* APK v1/v2 签名校验；v1 签名可供 Android 2.3 及 4.4.2 安装。
* **Android 2.3.6 (API 9) 真机验证：** 设备自签名证书成功生成并加载（无 `WRONG_TAG` 异常），HTTPS 53317 服务正常启动，成功发现官方 LocalSend 客户端并完成单文件及多文件 Chunked 上传与落盘，文件 SHA-256 校验一致。
* **Kindle Android 4.4.2 (API 19) 真机验证：** 启动、应用内文件选择，向 Android 11 设备发送文件成功；官方 LocalSend 1.17.0 (Android 11) 成功向 Kindle 发送文件并校验 SHA-256 一致。
* API 34 ARM64 模拟器冷启动、设备证书生成、HTTPS 53317 服务、前台 Service、`/info` 与 `/register` 接口响应及 Chunked 文件落盘一致性测试。

## Kindle 4.4.2 及低版本文件选择修复

Kindle 固件自带的 DocumentsUI 会保留已经被删除或移动的下载记录。旧版应用在打开对应 `content://` URI 时会收到 `FileNotFoundException`。API 9–20 改用应用内文件浏览器，直接列出实际存在且可读的外部存储文件；API 21+ 继续使用系统 SAF。对于其他无效文件来源，发送错误会显示明确的中文提示。

### 已实现但尚未真机验证

* Android 真机上的 Wi-Fi 频繁切换、厂商极高压后台限制和大文件长时间传输。

### 尚未实现

* LocalSend Reverse Download API（浏览器下载）；核心 Android-to-LocalSend Upload API 不依赖它。
* PIN、历史记录、文字分享、剪贴板、主题、自动更新、统计和账户等非核心功能。
* 子网逐地址扫描回退；当前使用官方默认组播发现和 `/register` 双向确认。

### 因环境限制无法验证

* Apple Silicon 主机的 Android Emulator 36 不支持 API 19 及 API 9 的 ARM 镜像，启动时会提示 CPU 架构不支持。

## API 19–20 接收模式的安全边界

Android 4.4 的系统 TLS 服务端与 LocalSend 1.17.0 没有共同密码套件，因此 API 19–20 只能以协议规定的 HTTP 模式接收。该方向的文件内容和元数据不会被 TLS 加密；应用仍检查来源 IP、随机会话 ID 和逐文件随机 token。请只在可信局域网使用。LegacySend 向其他设备发送以及 API 9–18、API 21+ 的接收服务均保持 HTTPS 和证书指纹固定。

协议研究和端点细节见 [docs/protocol.md](https://www.google.com/search?q=docs/protocol.md)。

## 贡献

欢迎提交 Issue 和 Pull Request。LegacySend 的首要目标是在 Android 2.3 (API 9) 及 Kindle Android 4.4.2 (API 19) 等旧设备上提供精简、稳定且可与官方 LocalSend (v2.2) 互通的文件传输能力；修改时请优先保护旧系统兼容性，而不是扩大功能范围。

### 实现边界

* 保持独立实现：不要导入、复制或构建 LocalSend 的源码、资源、模块或内部库。可以依据公开协议文档、公开源码中的协议行为以及黑盒测试进行兼容实现。
* 业务代码使用 Java 和传统 Android View，不引入 Kotlin、Compose、Flutter、React Native 或 Google Play 服务。
* 保持 `minSdkVersion 9`。调用更高版本 API 前必须做版本判断，并为低版本 (API 9/19) 提供可用路径。
* 运行时优先使用 Android SDK 和 Java 标准库。新增依赖必须说明用途、体积以及低版本 API 兼容性。
* 核心范围是设备发现、文件发送/接收、确认/拒绝/取消、进度和错误处理；历史记录、账户、云中转、自动更新等不属于当前目标。

### 开发要求

* 协议端口、请求路径、JSON 字段、状态码、证书指纹和版本协商必须有公开协议或实际通信行为作为依据，避免凭印象实现。
* 网络与文件 I/O 必须在后台线程运行；文件应流式处理，不能完整载入内存，并正确关闭 Socket 和流。
* 修改存储、通知、TLS、文件选择或生命周期逻辑时，应分别检查 API 9、API 19 和现代 Android 的行为。
* 保持模块职责清晰：发现、协议、安全、服务端、存储、传输和 UI 逻辑不要集中到单个 Activity。
* 测试截图、Gradle 缓存、构建目录和本机配置不要提交；Gradle Wrapper 的脚本、JAR 和配置应保留，以支持干净环境构建。

### 提交前检查

运行完整的本地检查：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug

```

与协议或传输有关的改动应补充测试，至少覆盖相关的序列化、中文/特殊字符文件名、多文件会话、接受/拒绝/取消、超时、流式复制和内容一致性场景。提交说明或 PR 中请明确区分：

* 已通过自动化测试的内容；
* 已在 Android 真机或模拟器验证的内容；
* 已与官方 LocalSend 实际互传验证的内容；
* 尚未验证的风险或环境限制。

不要把编译成功等同于低版本 Android 真机兼容，也不要把单元测试通过描述为已经完成官方 LocalSend 互传验证。