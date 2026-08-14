package com.isaver.filemanager.remote

enum class RemoteSecurityError {
    INVALID_HOST,
    INVALID_PORT,
    MISSING_USERNAME,
    MISSING_SECRET_REFERENCE,
    HOST_KEY_PIN_REQUIRED,
    CERTIFICATE_PIN_REQUIRED,
    PLAINTEXT_FTP_NOT_ACKNOWLEDGED,
}

class RemoteSecurityException(
    val code: RemoteSecurityError,
    message: String,
) : IllegalArgumentException(message)

object RemoteSecurityPolicy {
    fun validate(profile: RemoteProfile): Result<Unit> = runCatching {
        require(profile.host.isNotBlank()) {
            throw RemoteSecurityException(RemoteSecurityError.INVALID_HOST, "服务器地址不能为空")
        }
        require(profile.port in 1..65_535) {
            throw RemoteSecurityException(RemoteSecurityError.INVALID_PORT, "服务器端口无效")
        }
        require(profile.username.isNotBlank()) {
            throw RemoteSecurityException(RemoteSecurityError.MISSING_USERNAME, "用户名不能为空")
        }
        require(profile.secretRef.isNotBlank()) {
            throw RemoteSecurityException(RemoteSecurityError.MISSING_SECRET_REFERENCE, "凭据引用不能为空")
        }
        when (profile.protocol) {
            RemoteProtocol.SFTP -> requirePin(
                profile.hostKeyFingerprint,
                RemoteSecurityError.HOST_KEY_PIN_REQUIRED,
                "SFTP 必须配置主机密钥指纹",
            )
            RemoteProtocol.FTPS -> requirePin(
                profile.certificateFingerprint,
                RemoteSecurityError.CERTIFICATE_PIN_REQUIRED,
                "FTPS 必须配置服务器证书指纹",
            )
            RemoteProtocol.FTP -> if (!profile.allowPlaintext) {
                throw RemoteSecurityException(
                    RemoteSecurityError.PLAINTEXT_FTP_NOT_ACKNOWLEDGED,
                    "普通 FTP 可能明文传输，请确认风险后继续",
                )
            }
        }
    }

    private fun requirePin(value: String?, code: RemoteSecurityError, message: String) {
        if (value.isNullOrBlank()) throw RemoteSecurityException(code, message)
    }
}
