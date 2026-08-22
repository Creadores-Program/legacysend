# LocalSend Protocol Implementation Specification

## Information Sources

This implementation was developed independently using publicly available documentation, without copying LocalSend source code or assets:

* [Official LocalSend Protocol v2.2 Repository](https://github.com/localsend/protocol)
* [Official LocalSend Protocol v2.2 README](https://github.com/localsend/protocol/blob/main/README.md)
* [Official LocalSend Project Overview](https://github.com/localsend/localsend)
* [Android `KeyPairGeneratorSpec` API](https://developer.android.com/reference/android/security/KeyPairGeneratorSpec)
* [Android Storage Access Framework (SAF)](https://developer.android.com/guide/topics/providers/document-provider)

Additionally, solely to confirm TLS client certificate behavior in current clients, publicly available code in the LocalSend repository handling HTTP, discovery, and certificate generation was reviewed. Building and running this project does not reference these files.

## Implementation Version

Implements the v2 REST API as defined in the LocalSend Protocol v2.2 specification. The online `version` field is sent as `2.2`. Chunked Transfer Encoding is fully supported.

Default parameters:

| Parameter | Value |
| --- | --- |
| minSdk | `9` (Android 2.3 Gingerbread) |
| UDP Multicast Group | `224.0.0.167` |
| UDP Port | `53317` |
| Receiving Port | `53317` |
| Device Type | `mobile` |
| Transmission Mode | HTTP for reception on API 19–20; HTTPS for reception on API 9–18, API 21+, and all outgoing transfers |

## Discovery Process

1. Start the receiving service and acquire the Android `MulticastLock`.
2. Join `224.0.0.167:53317` and broadcast a UTF-8 JSON announcement with `announce: true`.
3. Upon receiving announcements from other devices, record their details indexed by IP and certificate fingerprint.
4. Respond to `announce: true` packets with a multicast reply containing `announce: false`, and call the remote device's `POST /api/localsend/v2/register`.
5. The local HTTP/HTTPS service also handles incoming `/register` calls from official clients, returning local device metadata.

The device fingerprint is the uppercase hexadecimal SHA-256 hash of the local self-signed X.509 DER certificate. It prevents self-discovery and locks the peer HTTPS identity (*certificate pinning*).

## Sending Flow

1. Read the selected file's name, MIME type, and size via SAF or the legacy file system, assigning a unique UUID to each file.
2. Send a `POST /api/localsend/v2/prepare-upload` request to the receiver containing `info` and a `files` object indexed by file ID.
3. Wait for user confirmation to obtain the `sessionId` and per-file tokens.
4. Sequentially stream data using `POST /api/localsend/v2/upload?sessionId=...&fileId=...&token=...` (supporting Chunked stream uploads).
5. On cancellation, disconnect active sockets and invoke `POST /api/localsend/v2/cancel?sessionId=...`.

The HTTPS client pins the peer certificate's SHA-256 fingerprint obtained from the UDP announcement without relying on hostname matching. Requests simultaneously present the local KeyStore certificate as the client identity.

## Receiving Flow

The local `ServerSocket` exposes the following endpoints:

* `GET /api/localsend/v2/info`
* `POST /api/localsend/v2/register`
* `POST /api/localsend/v2/prepare-upload`
* `POST /api/localsend/v2/upload`
* `POST /api/localsend/v2/cancel`

`prepare-upload` waits up to 5 minutes for user confirmation. Upon acceptance, it generates unique random tokens for the session and each file. During upload, it validates:

* Session existence and acceptance status.
* File ID and token matching.
* Source IP matching the initial prepare request IP.
* TLS client certificate SHA-256 fingerprint matching the device fingerprint in the JSON payload (when present).
* `Content-Length` header matching metadata, or streaming Chunked data until EOF matches expected size.

Incoming data is initially written to a hidden `.part` file and atomically renamed upon completion. Interrupted or canceled transfers trigger temporary file cleanup. Target filenames are pre-reserved to prevent race conditions during parallel uploads.

### TLS Certificate Generation & Compatibility (Android 2.3–4.2 / API 9–17)

On API 18+, the system natively uses `AndroidKeyStore` for KeyPair generation. On API 9–17 (Android 2.3–4.2), due to the lack of `AndroidKeyStore` and strict DER/ASN.1 parsing in legacy OpenSSL / Conscrypt, a custom manual generation strategy is implemented:

1. **KeyStore Format:** Uses `BouncyCastle` (`BKS`) stored in app-private storage, bypassing `PKCS12` serialization crashes on Android 2.3.

### Android 4.4 TLS Reception Compatibility

Tests on Kindle Android 4.4.2 showed that its TLS 1.2 server supports `TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA`, but lacks `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`. Because the Rust TLS client in LocalSend 1.17.0 dropped CBC cipher suites, no common ciphers exist, resulting in a `HandshakeFailure` before HTTP processing. LocalSend v2 allows broadcasting `protocol: "http"`, which is used as the official fallback on API 19–20; API 9–18 and API 21+ enforce HTTPS with pinned fingerprints.

HTTP mode does not encrypt transit data or validate TLS client certificates; however, it strictly verifies source IP, session IDs, and per-file tokens. This mode is restricted to API 19–20 and should only be used on trusted local networks.

Successful real-device single and multi-file tests have been completed across Android 2.3.6 (API 9), Kindle Android 4.4.2, and official LocalSend 1.17.0 (Android 11): discovery, `prepare-upload`, manual acceptance, Chunked streaming, and disk writing all completed with matching SHA-256 hashes.

## Status Codes

* `200`: Success (request or upload complete).
* `204`: No files required for transfer.
* `400`: Invalid JSON request, parameters, or file size.
* `403`: Denied, or mismatch in token, IP, or certificate identity.
* `404`: Endpoint not found.
* `409`: Pending session confirmation exists or session canceled.
* `500`: Internal server, network, or storage error.

## Pending Verification Risks

* Variations in TLS protocol versions or client certificate negotiation across different official LocalSend release builds.
* Multicast packet filtering by certain routers or Android vendors; subnet IP scanning fallback is not currently implemented.
* Official clients support parallel uploads; while the receiver handles concurrent progress tracking and filename reservation, heavy stress testing against official clients is pending.