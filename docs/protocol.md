# LocalSend 协议实现说明

## 信息来源

本实现根据公开资料独立编写，没有复制 LocalSend 的源码或资源：

* [LocalSend Protocol v2.2 官方仓库](https://github.com/localsend/protocol)
* [LocalSend Protocol v2.2 官方 README](https://github.com/localsend/protocol/blob/main/README.md)
* [LocalSend 官方项目说明](https://github.com/localsend/localsend)
* [Android `KeyPairGeneratorSpec` API](https://developer.android.com/reference/android/security/KeyPairGeneratorSpec)
* [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)

此外，仅为确认当前客户端的 TLS 客户端证书行为，阅读了公开 LocalSend 仓库中负责 HTTP、发现和证书生成的实现；项目构建和运行不引用这些文件。

## 实现版本

实现 LocalSend Protocol v2.2 文档定义的 v2 REST API，线上 `version` 字段按文档发送 `2.2`。支持分块传输编码（Chunked Transfer Encoding）。

默认参数：

| 项目 | 值 |
| --- | --- |
| minSdk | `9` (Android 2.3 Gingerbread) |
| UDP 组播组 | `224.0.0.167` |
| UDP 端口 | `53317` |
| 接收端口 | `53317` |
| 设备类型 | `mobile` |
| 传输方式 | API 19–20 接收使用 HTTP；API 9–18 及 API 21+ 接收与所有对外发送使用 HTTPS |

## 发现流程

1. 启动接收服务并获取 Android `MulticastLock`。
2. 加入 `224.0.0.167:53317`，发送含 `announce: true` 的 UTF-8 JSON 公告。
3. 收到其他设备公告后，按证书指纹和 IP 记录设备。
4. 对 `announce: true` 公告发送 `announce: false` 的组播回包，并调用对方 `POST /api/localsend/v2/register`。
5. 本地 HTTP/HTTPS 服务也处理官方客户端发来的 `/register`，返回本机设备信息。

设备指纹为本机自签名 X.509 证书 DER 内容的 SHA-256 大写十六进制值，用于避免自发现并固定 HTTPS 对端身份。

## 发送流程

1. 通过 SAF 或低版本文件系统读取所选文件的名称、MIME 类型和字节数，为每个文件生成 UUID。
2. 向接收方 `POST /api/localsend/v2/prepare-upload` 发送 `info` 和按文件 ID 索引的 `files` 对象。
3. 等待用户确认后取得 `sessionId` 和每个文件的 token。
4. 顺序流式调用 `POST /api/localsend/v2/upload?sessionId=...&fileId=...&token=...`（支持 Chunked 流式上传）。
5. 取消时断开活动连接并调用 `POST /api/localsend/v2/cancel?sessionId=...`。

HTTPS 客户端固定 UDP 公告中的证书 SHA-256 指纹，不依赖主机名匹配；请求同时使用本机 KeyStore 证书作为客户端身份。

## 接收流程

本机 ServerSocket 提供以下接口：

* `GET /api/localsend/v2/info`
* `POST /api/localsend/v2/register`
* `POST /api/localsend/v2/prepare-upload`
* `POST /api/localsend/v2/upload`
* `POST /api/localsend/v2/cancel`

`prepare-upload` 最多等待用户 5 分钟。接受后为会话和每个文件生成独立随机令牌。上传时检查：

* 会话存在且已接受；
* 文件 ID 和 token 匹配；
* 上传 IP 与准备请求 IP 相同；
* TLS 客户端证书存在时，其 SHA-256 指纹与 JSON 设备指纹一致；
* 校验 `Content-Length` 或接收 Chunked 流直到 EOF，且数据大小与元数据一致。

内容先写入隐藏的 `.part` 文件，完整接收后原子重命名到最终名称；中断或取消会删除临时文件。同名目标先预留，避免并行上传互相覆盖。

### TLS 证书生成与兼容性 (Android 2.3–4.2 / API 9–17)

在 API 18+ 上，系统使用 `AndroidKeyStore` 原生生成 KeyPair；而在 API 9–17 (Android 2.3–4.2) 上，为解决系统缺乏 `AndroidKeyStore` 且 OpenSSL / Conscrypt 解析 DER/ASN.1 结构极为严格的问题，采用了以下自定义生成策略：

1. **Almacén de Claves (KeyStore)：** 使用 `BouncyCastle` (`BKS`) 格式作为本地 App 私有文件存储，解决 Android 2.3 上 `PKCS12` 写入与反序列化崩溃问题。

### Android 4.4 TLS 接收兼容

Kindle Android 4.4.2 的 TLS 1.2 服务端实测可用 `TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA`，但不支持 `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`。LocalSend 1.17.0 的 Rust TLS 客户端已不支持 CBC 套件，因此双方没有共同套件，HTTPS 会在 HTTP 请求前收到 `HandshakeFailure`。LocalSend v2 协议允许设备公告 `protocol: "http"`，版本 1.2 在 API 19–20 使用这一官方兼容模式；API 9–18 及 API 21+ 仍使用证书指纹固定的 HTTPS。

HTTP 接收模式不加密传输内容，也无法校验 TLS 客户端证书；接收端仍校验来源 IP、随机会话 ID 和逐文件随机 token。该模式仅用于 API 19–20，应只在可信局域网使用。所有对外发送及 API 9–18、API 21+ 接收保持 HTTPS。

在 Android 2.3.6 (API 9)、Kindle Android 4.4.2 与官方 LocalSend 1.17.0 (Android 11) 上已完成单文件与多文件实测：发现、`prepare-upload`、人工接受、Chunked 上传和落盘均成功，接收文件 SHA-256 与源文件一致。

## 状态码

* `200`：请求或上传成功。
* `204`：没有需要传输的文件。
* `400`：请求 JSON、参数或文件大小无效。
* `403`：拒绝、令牌/IP/证书身份不匹配。
* `404`：接口不存在。
* `409`：已有待确认会话或会话已取消。
* `500`：保存、网络或服务内部错误。

## 尚待验证的风险

* 不同 LocalSend 发布版使用的 TLS 协议和客户端证书协商细节可能有差异。
* 部分路由器或 Android 厂商会过滤组播，当前未实现逐 IP 子网扫描回退。
* 官方客户端支持并行上传；接收端已为并发进度和重名预留做处理，但尚未用官方客户端做压力测试。