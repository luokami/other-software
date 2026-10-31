# 小店账本原生版 · 1.3.0

面向 Android 8.0 及以上的离线记账应用。界面使用 Kotlin / Jetpack Compose，账本保存在本机 Room / SQLite；没有联网权限，不依赖网页服务器。

## 使用与升级

[下载安装包](https://github.com/luokami/other-software/releases/download/v1.3.0/xiaodian-ledger-native-v1.3.0.apk) · [发布说明](https://github.com/luokami/other-software/releases/tag/v1.3.0)

- 从 **1.2.0** 升级：直接覆盖安装，保留包名 `cn.shopledger.offline` 和原签名。首次启动自动导入旧账目、借款、提醒、自定义分类、设置、密码验证器及本机自动备份；原数据库和旧设置不删除，原桌面启动入口继续兼容。
- 从 **1.1.0** 升级：该历史版本使用不同包名，请先从旧应用导出完整 JSON，再在原生版“设置 → 备份与恢复”导入。
- 首次迁移未完成时提供重试，完整事务提交后才进入账本，避免失败后显示空账本。
- 自动备份在本机保留最近 7 个发生变化的日期。卸载或更换手机前，请导出完整 JSON 到应用外。CSV 用于查看，不能作为完整恢复备份。
- 隐私密码不随 JSON 导出。锁定后只能查看首页；返回后台会重新锁定。

## 功能

首页分类直接录入，独立固定 / 额外收入，常填内容快捷选取；单行借款、一级还款、分次还款；今日 / 本月 / 本年 / 自定义统计；按日折叠明细、分类筛选和搜索；删除确认、撤销、回收站；五档字号、浅深色、首次教程和应用内付款提醒。

提醒在打开应用时检查，不提供后台定时通知。借出和还款始终独立，不计入收支总额。恢复前自动保留当前账本的保护备份。

## 构建

JDK 17、Android SDK 35。Gradle Wrapper 会获取固定 Gradle 8.11.1。

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

发布包必须沿用原签名。设置环境变量 `SHOP_LEDGER_SIGNING_DIR` 指向包含 `shop-ledger.p12` 与 `password.txt` 的私有目录；别名 `shop-ledger`，格式 PKCS12。未提供时仅生成未签名包，不能覆盖正式安装。密钥和密码不提交仓库。

源码分层：`LedgerData` 数据校验与规则、`LedgerRepository` 原子持久化及迁移、`LedgerModel` 操作与锁状态、Compose 页面 / 弹窗。首次升级的隐藏 WebView 仅用于读取旧 localStorage / IndexedDB，正常使用和所有界面均不使用 WebView。

[迁移验收](MIGRATION.md) · [需求演进更新表](../shop-ledger-offline/CHANGELOG.md)
