# 原生迁移验收清单

目标版本 1.3.0，Kotlin + Jetpack Compose + Room。应用标识 cn.shopledger.offline，复用 1.2.0 签名，versionCode 33。

- [ ] 编译、签名校验，证书与 1.2.0 一致
- [ ] 原生首页、分类录入、收入、借款、还款、按日明细与汇总
- [ ] 搜索、字号、深色、隐私锁、提醒与教程
- [ ] SQLite 原账本保留，Room 原子导入；失败不启用空账本
- [ ] 旧 WebView 设置、密码验证器、自动备份一次性迁移
- [ ] JSON 备份往返兼容、CSV 导出、回收站、恢复前保护备份
- [ ] 数据逻辑、迁移、原生界面测试通过

迁移过程中不删除原 ledger.db 或 WebView 数据。WebView 仅在首次升级时作为隐藏兼容工具读取旧设置与 IndexedDB，所有用户界面均为 Compose。
