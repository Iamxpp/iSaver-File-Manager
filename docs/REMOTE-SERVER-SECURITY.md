# iSaver 远程服务器安全边界

当前远程连接支持 SFTP、FTPS 和普通 FTP。所有连接都通过 `RemoteProfile`、`RemoteSecurityPolicy` 和协议适配器创建，UI 不接触 Shell 命令、原始 socket 或密码明文。

- SFTP 必须配置主机密钥 SHA-256 指纹；JSch 使用严格主机密钥检查，指纹不匹配时连接阻断。
- FTPS 使用系统信任链、主机名校验和叶证书 SHA-256 指纹固定；不接受信任所有证书的回退路径。
- 普通 FTP 必须在 UI 中明确确认明文传输风险，连接配置不会默认启用。
- 密码只通过 Android Keystore AES-GCM 加密后保存，日志和异常消息不得包含密码、令牌或完整远程路径。
- 上传和下载使用流式适配器，连接断开只返回失败，不自动重放可能已完成的远程写入。
- 远程适配器只接受结构化 `RemotePath`；路径中的 NUL 字符被拒绝。
