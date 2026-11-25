# TokenSeek 安卓客户端

TokenSeek 是一个独立的 DeepSeek 安卓客户端。聊天界面基于官方开源的 `chat-demo` v0.1.4，随 APK 一起安装。聊天直接使用你选中的开放平台 API Key，按 DeepSeek 的 API 计价规则结算。[官方聊天 Demo](https://github.com/deepseek-ai/chat-demo/tree/v0.1.4) · [官方 API 计价](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)

## 登录、充值和密钥

1. 从聊天侧栏左下角点头像，进入「DeepSeek 账户」。点「登录 DeepSeek」后在应用内的官方页面登录，顶部会显示当前域名。[开放平台登录](https://platform.deepseek.com/sign_in)
2. 在侧栏右下角点「⋮ → 钱包 → 充值」，在官方页面核对账户、金额和支付方式。官网要求重新登录时，应用会记住充值任务。支付宝和微信可从官方收银台唤起。手机无法打开支付应用时，有官方网页回退地址的链接可继续网页支付，也可重新打开官方充值页。返回后可以核对到账情况。[官方充值页](https://platform.deepseek.com/top_up)
3. 在「设置 → API 密钥」打开官方密钥管理页，可查看、创建或撤销该官方账户的密钥。要将新密钥用于聊天，在官网复制，点「用于聊天」，返回后粘贴并验证。已有密钥也可直接粘贴验证。应用使用当前选中的密钥发消息、查询余额。[官方密钥管理](https://platform.deepseek.com/api_keys) · [首次调用 API](https://api-docs.deepseek.com/zh-cn/)

「API 密钥」按本机账户展示保存的全部密钥。可命名、切换、取消选用或移除，其他账户的列表显示名称和尾号，切换账户即可编辑。密钥在手机上使用 Android Keystore 加密保存。要停止一把密钥在所有设备上的使用，请在官方密钥管理页撤销它。

钱包按币种显示总余额、充值余额和赠金。侧栏昵称下方也会显示已连接账户的余额。支付应用返回时会重新查询余额，到账和订单状态以官方平台为准。[官方余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)

## 侧栏、账户和项目

- 在聊天页向右拖动即可跟手拉开侧栏，向左拖动收起。点聊天页右上角可新建对话。
- 在会话的更多菜单中可以置顶、移入或移出项目。项目内置顶的对话排在该项目最前，项目外的置顶对话集中显示，其余未分组对话列在「最近对话」。删除项目会保留其中的对话。
- 点侧栏左下角头像可以修改本机用户名、昵称和圆形头像，也可新建或切换本机账户。昵称默认采用用户名。每个账户有自己的会话、草稿、附件、项目、密钥和回答偏好。原有记录保留在默认账户。头像只在本机裁剪、保存。
- 每个本机账户都可登录对应的官方平台账户。支持多配置的 Android System WebView 可分别保存各账户的官网登录状态。旧版 WebView 共用网页登录时，官方窗口会持续提示核对账号。若选择外部浏览器继续登录或支付，也要在浏览器中核对账号。

## 聊天、上下文和设置

输入框可直接选择 Flash 或 Pro。新会话默认预选 Flash，已有会话分别保存模型选择。重新生成时使用当前模型，续写沿用原回答的模型。Flash 可使用智能搜索、链接阅读与附件问答。Pro 支持文本对话与深度思考。上游 Demo 还提供流式输出、来源引用、语音输入与朗读及消息编辑。[官方聊天指南](https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/) · [DSH 模型选择](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-model-selection/README.md)

输入框内的圆环表示当前分支预计剩余的上下文。点圆环可查看剩余空间、草稿估算，以及整个会话和每次回答的实际用量；重新生成的其他分支也算入会话累计。实际用量采用 API 返回的 token 记录。附件内容等无法可靠估算时，圆环显示「—」，详情会说明原因。[官方模型列表](https://api-docs.deepseek.com/zh-cn/api/list-models/) · [用量字段](https://api-docs.deepseek.com/zh-cn/api/create-message/)

设置首页、外观、字体大小和个性化使用 Android 原生控件。可以切换跟随系统、浅色或深色，调整四档字号，以及为每个账户设置回答风格与默认回答语言。聊天正文和本机管理页面由 APK 内置界面渲染。开放平台的身份验证、收款和官方密钥签发由应用内官方页面完成。

## 对话导入与导出

在「设置 → 数据管理」导出当前账户的完整 ZIP，在单个对话的更多菜单中导出该对话。备份含项目、置顶状态及本机已有的附件原件，密钥与登录凭证留在本机。支持导入兼容的 DeepSeek ZIP 和旧版 TokenSeek 备份。

默认选择「合并导入」会保留已有的同 ID 会话。「覆盖恢复」需明确选择，会替换当前本机账户的记录。其他账户的数据保持独立。对话历史保存在手机上。

## 待办

通过 DeepSeek 网页端账户直接聊天、无需开放平台 API Key 的模式已列入待办。0.6.0 的聊天仍使用开放平台 API。

## 构建安卓版

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、Node.js 24、npm、Git 和 Bash。设置 `ANDROID_HOME` 或在 `local.properties` 指定 `sdk.dir`。首次构建需联网获取依赖与锁定版本的上游代码。

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

调试安装包生成在 `app/build/outputs/apk/debug/app-debug.apk`，最低支持 Android 8.0。聊天界面需要较新的 Android System WebView。Gradle 会应用 TokenSeek 的补丁，执行 TypeScript 检查，并将聊天资源和上游许可证一起打包。

| 部分 | 源码 |
| --- | --- |
| 聊天页、侧栏、模型与用量 | `web/overlay/src/components/chatInput/`、`web/overlay/src/components/sider/`、`web/overlay/src/services/chat/` |
| 本机账户、钱包与密钥 | `web/overlay/src/components/settings/`、`web/overlay/src/services/profiles.ts`、`app/src/main/java/io/tokenseek/app/KeyVault.java` |
| Android 原生设置、官方页面和支付 | `app/src/main/java/io/tokenseek/app/NativeSettings.java`、`app/src/main/java/io/tokenseek/app/OfficialPlatformActivity.java`、`app/src/main/java/io/tokenseek/app/PaymentHandoff.java` |
| 官方 Demo 版本、差异及打包 | `tools/build-web.sh`、`web/patches/` |
