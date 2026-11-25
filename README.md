# TokenSeek 安卓客户端

TokenSeek 是独立的 DeepSeek Android 客户端。0.10.0 使用 Android 原生控件绘制聊天、侧栏、设置、用量、账户、密钥、数据管理和充值界面。聊天使用当前账户选用的开放平台 API 密钥。[DeepSeek API](https://api-docs.deepseek.com/zh-cn/)

## 账户与聊天

在侧栏点头像打开「账户」，点右下角菜单直接打开「设置」。「切换账户」位于设置首页底部。各本机账户分别保存昵称、头像、API 密钥、对话、草稿、项目和回答偏好。昵称默认使用用户名。外观、字体大小、个性化和通用偏好可在设置中调整。

「聊天密钥」支持添加、验证、选用和移除本机密钥。密钥通过 Android Keystore 加密保存。当前公开 API 没有提供第三方管理开放平台全部密钥的授权流程。「开放平台密钥」可由用户选择在系统浏览器打开 DeepSeek 官方管理页，再回到软件绑定已创建的密钥。[开放平台密钥](https://platform.deepseek.com/api_keys)

在输入框选择 Flash 或 Pro，点右上角创建对话。侧栏支持拖动、置顶及项目分组。聊天支持流式响应、停止生成、分支以及设备提供的语音功能。Flash 支持文件与官方网页工具。[官方聊天 Demo](https://github.com/deepseek-ai/chat-demo) · [官方工具文档](https://api-docs.deepseek.com/guides/web_tools/)

## 用量

点输入框的圆环打开原生「对话用量」，查看本分支的上下文估算与已记录的请求用量。从「账户 → 用量」打开原生账户统计，可汇总该本机账户保存及导入的会话。已确认的 token 使用官方响应中的最终记录；上下文剩余值为估算，缺少最终记录的请求单独标记。本机汇总的范围不包括其他设备或其他应用发出的请求。[用量字段](https://api-docs.deepseek.com/zh-cn/api/create-message/)

## 余额与充值

「账户 → 余额与充值」从官方余额 API 按币种读取总余额、充值余额和赠金。可选 ¥10、¥50、¥100 或自定义金额。金额准备、确认和复制使用原生界面。创建真实订单仍需用户主动在外部 DeepSeek 官方收银台核对金额并选支付方式；原生金额不会自动写入官网订单。当前没有接入面向该客户端的原生商户下单或官方账单 API。返回应用后会重新查询余额。[官方余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/) · [官方充值](https://platform.deepseek.com/top_up) · [官方账单](https://platform.deepseek.com/transactions)

## 备份与升级

从 0.9.2 升级且希望保留账户、聊天、草稿、附件和设置时，请在原设备覆盖安装同签名的 0.9.3 离线迁移工具，点「准备迁移」。看到已准备的对话数后，覆盖安装 0.10.0，在首次打开时确认合并。保留应用数据及原有签名，密钥可沿用原来的 Android Keystore 条目。[迁移说明](migration/README.md)

如果已从旧版导出聊天 ZIP，可以直接在新版「设置 → 数据管理」选择对应账户的备份。默认合并会保留同 ID 的已有会话，覆盖前会再次确认。聊天 ZIP 不含 API 密钥和登录凭据。

## 构建

需要 JDK 17、Android SDK Platform 35 与 Build Tools 35.0.0。最低支持 Android 8.0。运行以下命令生成 Android 调试包并检查原生界面约束。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。应用界面的长期约束见 [AGENTS.md](AGENTS.md)。
