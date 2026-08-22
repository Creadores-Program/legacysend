# Especificación de la Implementación del Protocolo LocalSend

## Fuentes de Información

Esta implementación fue desarrollada de forma independiente con base en documentación pública, sin copiar código fuente ni recursos de LocalSend:

* [Repositorio Oficial de LocalSend Protocol v2.2](https://github.com/localsend/protocol)
* [README Oficial de LocalSend Protocol v2.2](https://github.com/localsend/protocol/blob/main/README.md)
* [Descripción del Proyecto Oficial de LocalSend](https://github.com/localsend/localsend)
* [API `KeyPairGeneratorSpec` de Android](https://developer.android.com/reference/android/security/KeyPairGeneratorSpec)
* [Android Storage Access Framework (SAF)](https://developer.android.com/guide/topics/providers/document-provider)

Adicionalmente, y solo para verificar el comportamiento de los certificados de cliente TLS, se revisó la implementación pública del repositorio de LocalSend encargada de HTTP, descubrimiento y generación de certificados. La compilación y ejecución de este proyecto no utiliza ni referencia dichos archivos.

## Versión de la Implementación

Implementa la API REST v2 definida en la documentación del protocolo LocalSend v2.2. El campo `version` se envía como `2.2` según la especificación. Soporta codificación de transferencia por bloques (`Chunked Transfer Encoding`).

Parámetros por defecto:

| Parámetro | Valor |
| --- | --- |
| minSdk | `9` (Android 2.3 Gingerbread) |
| Grupo Multicast UDP | `224.0.0.167` |
| Puerto UDP | `53317` |
| Puerto de Recepción | `53317` |
| Tipo de Dispositivo | `mobile` |
| Modo de Transmisión | HTTP para recepción en API 9–20; HTTPS para recepción en API 21+ y todos los envíos salientes |

## Flujo de Descubrimiento

1. Se inicia el servicio de recepción y se adquiere el `MulticastLock` de Android.
2. El dispositivo se une al grupo `224.0.0.167:53317` y envía un anuncio JSON en UTF-8 con `announce: true`.
3. Al recibir un anuncio de otro dispositivo, este se registra indexado por su IP y la huella digital (*fingerprint*) de su certificado.
4. Ante un anuncio con `announce: true`, se responde con un paquete multicast con `announce: false` y se realiza una petición `POST /api/localsend/v2/register` al destinatario.
5. El servicio HTTP/HTTPS local también procesa las peticiones `/register` entrantes del cliente oficial, devolviendo la información del dispositivo local.

La huella del dispositivo es el hash SHA-256 en hexadecimal mayúscula del contenido DER del certificado X.509 autofirmado. Se utiliza para evitar el autodescubrimiento y fijar la identidad HTTPS del extremo (*certificate pinning*).

## Flujo de Envío

1. Se lee el nombre, tipo MIME y tamaño en bytes del archivo seleccionado mediante SAF o el sistema de archivos legado, generando un UUID para cada archivo.
2. Se envía una petición `POST /api/localsend/v2/prepare-upload` al receptor con el objeto `info` y la lista de `files` indexada por ID.
3. Se espera la confirmación del usuario para obtener el `sessionId` y el token de cada archivo.
4. Se realizan llamadas en flujo secuencial a `POST /api/localsend/v2/upload?sessionId=...&fileId=...&token=...` (compatible con subida en flujo *Chunked*).
5. En caso de cancelación, se cierran las conexiones activas y se llama a `POST /api/localsend/v2/cancel?sessionId=...`.

El cliente HTTPS fija la huella SHA-256 del certificado obtenida en el anuncio UDP, sin depender de la coincidencia del nombre de host. La petición utiliza el certificado del KeyStore local como identidad de cliente.

## Flujo de Recepción

El `ServerSocket` local expone las siguientes interfaces:

* `GET /api/localsend/v2/info`
* `POST /api/localsend/v2/register`
* `POST /api/localsend/v2/prepare-upload`
* `POST /api/localsend/v2/upload`
* `POST /api/localsend/v2/cancel`

La interfaz `prepare-upload` espera la confirmación del usuario un máximo de 5 minutos. Al aceptar, genera tokens aleatorios independientes para la sesión y para cada archivo. Durante la subida se verifica:

* Que la sesión exista y haya sido aceptada.
* Que el ID de archivo y el token coincidan.
* Que la IP de subida sea idéntica a la IP que inició la solicitud.
* Si existe certificado de cliente TLS, que su huella SHA-256 coincida con la huella declarada en el JSON del dispositivo.
* Validación del encabezado `Content-Length` o recepción del flujo *Chunked* hasta EOF, asegurando que el tamaño coincida con los metadatos.

El contenido se escribe primero en un archivo oculto `.part` y, tras completarse la recepción, se le cambia el nombre de forma atómica. Si la transferencia se interrumpe o cancela, el archivo temporal se elimina. Los nombres duplicados se reservan previamente para evitar sobreescrituras en subidas paralelas.

### Generación de Certificados TLS y Compatibilidad (Android 2.3–4.2 / API 9–17)

En API 18+, el sistema utiliza `AndroidKeyStore` de forma nativa para generar el par de claves. En API 9–17 (Android 2.3–4.2), debido a la ausencia de `AndroidKeyStore` y a que OpenSSL / Conscrypt interpreta las estructuras DER/ASN.1 de forma muy estricta, se implementó la siguiente estrategia de generación manual:

1. **Almacén de Claves (KeyStore):** Uso del formato `BouncyCastle` (`BKS`) almacenado en el directorio privado de la aplicación, solucionando fallos de escritura y deserialización de `PKCS12` en Android 2.3.

### Compatibilidad de Recepción TLS en Android 4.4

Pruebas en Kindle Android 4.4.2 mostraron que su servidor TLS 1.2 soporta `TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA`, pero carece de soporte para `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`. Dado que el cliente TLS en Rust de LocalSend 1.17.0 eliminó el soporte para cifrados CBC, ambas partes se quedan sin un *ciphersuite* en común, generando un `HandshakeFailure` antes de la petición HTTP. El protocolo LocalSend v2 permite anunciar el modo `protocol: "http"`, el cual se utiliza como modo de compatibilidad oficial en API 19–20; API 9–18 y API 21+ mantienen el uso de HTTPS con certificado fijado.

El modo de recepción HTTP no cifra el contenido ni puede validar el certificado TLS del cliente; sin embargo, valida la IP de origen, el ID de sesión aleatorio y los tokens individuales por archivo. Este modo es exclusivo para API 19–20 y debe usarse únicamente en redes locales de confianza.

Se han completado con éxito pruebas de transferencia de archivos individuales y múltiples en Android 2.3.6 (API 9), Kindle Android 4.4.2 y el cliente oficial de LocalSend 1.17.0 (Android 11): descubrimiento, `prepare-upload`, aceptación manual, subida por bloques (*Chunked*) y escritura en disco funcionaron correctamente, verificando la coincidencia del hash SHA-256 final.

## Códigos de Estado

* `200`: Solicitud o subida exitosa.
* `204`: No hay archivos requeridos para transferir.
* `400`: JSON de solicitud, parámetros o tamaño de archivo no válido.
* `403`: Rechazado, error de validación en token, IP o identidad de certificado.
* `404`: Endpoint no encontrado.
* `409`: Sesión pendiente de confirmación existente o sesión cancelada.
* `500`: Error interno del servidor, de red o de almacenamiento.

## Riesgos Pendientes de Verificación

* Diferentes versiones publicadas de LocalSend pueden presentar variaciones en la negociación TLS o certificados de cliente.
* Algunos routers o fabricantes de Android filtran el tráfico multicast; actualmente no se ha implementado un escaneo de respaldo por subred IP.
* El cliente oficial soporta subidas en paralelo; aunque el receptor gestiona la velocidad concurrente y reserva nombres, aún no se han realizado pruebas de carga intensivas con el cliente oficial.