# TokenSeek 安卓客户端

TokenSeek 是独立的 DeepSeek Android 客户端。0.10.1 使用 Android 原生控件绘制聊天、侧栏、设置、用量、账户、密钥和余额。聊天通过当前账户选用的开放平台 API 密钥调用。[DeepSeek API](https://api-docs.deepseek.com/zh-cn/)

## 账户与聊天

在侧栏点头像打开「账户」，点右下角菜单直接打开「设置」。「切换账户」位于设置首页底部。各本机账户分别保存昵称、头像、API 密钥、对话、草稿、项目和回答偏好。昵称默认使用用户名。外观、字体大小、个性化和通用偏好可在设置中调整。

在「账户 → 连接 DeepSeek API」输入已有密钥，应用会向 DeepSeek 验证，成功后通过 Android Keystore 加密保存。「聊天密钥」可切换密钥、修改本机备注和从当前设备移除密钥。该流程验证的是 API 调用权限。本机用户名和头像可以自行设置。DeepSeek 已公开的 DSH 账户模块描述了其自身 Web 和桌面客户端的授权流程，目前仓库没有分配给 TokenSeek 的登录配置或平台管理权限。[官方 API 认证](https://api-docs.deepseek.com/zh-cn/api/deepseek-api/) · [DSH 官方账户模块](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/account/deepseek-account/README.zh.md)

在输入框选择 Flash 或 Pro，点右上角创建对话。侧栏支持拖动、置顶及项目分组。聊天支持流式响应、停止生成、分支以及设备提供的语音功能。Flash 支持文件与官方网页工具。[官方聊天 Demo](https://github.com/deepseek-ai/chat-demo) · [官方工具文档](https://api-docs.deepseek.com/guides/web_tools/)

## 用量

目标是登录后在原生页面直接查询全账户用量，切换日期、密钥或刷新时向开放平台取数。当前版本尚未接入可供 TokenSeek 查询账户历史的官方授权接口。

在「账户 → 开放平台用量」选择 DeepSeek 导出的用量 ZIP，或解压后的 `amount-*.csv`。应用在设备上读取报表，按密钥和模型显示其中的请求与 token 用量。ZIP 含受支持的费用明细时，另按币种显示费用。报表可包含由其他设备和应用发起的请求，范围取决于导出时所选的时间窗和密钥。多份报表按本机账户保存，可切换查看。获取最新数据需要一份新的官方报表。[官方用量导出说明，见「如何分 key 查看用量」](https://static.deepseek.com/faq/index.html?lang=zh)

点聊天输入框的圆环可查看当前分支的上下文估算、整段对话或单条回复的用量。已确认的 token 取自 API 响应的最终记录，缺少最终记录的请求单独标记。[用量字段](https://api-docs.deepseek.com/zh-cn/api/create-message/)

## 余额与充值

「账户 → 余额」通过官方 API 按币种读取总余额、充值余额和赠金。应用内充值下单、唤起支付钱包、平台账户登录和远程管理全部密钥仍待取得适用于 TokenSeek 的官方接口。应用内已经移除这些流程的浏览器入口。[官方余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)

## 备份与升级

0.10.0 可直接覆盖升级。若从 0.9.2 升级且希望保留账户、聊天、草稿、附件和设置，请在原设备覆盖安装同签名的离线迁移工具，点「准备迁移」。看到已准备的对话数后，覆盖安装 0.10.1，在首次打开时确认合并。保留应用数据及原有签名，密钥可沿用原来的 Android Keystore 条目。[迁移说明](migration/README.md)

如果已从旧版导出聊天 ZIP，可以直接在新版「设置 → 数据管理」选择对应账户的备份。默认合并会保留同 ID 的已有会话，覆盖前会再次确认。聊天 ZIP 不含 API 密钥和登录凭据。

## 构建

需要 JDK 17、Android SDK Platform 35 与 Build Tools 35.0.0。最低支持 Android 8.0。运行以下命令生成 Android 调试包并检查原生界面约束。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。应用界面的长期约束见 [AGENTS.md](AGENTS.md)。
