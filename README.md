# TokenSeek 安卓客户端

TokenSeek 是一个独立的 DeepSeek 安卓客户端。聊天界面基于官方开源的 `chat-demo` v0.1.4，随 APK 一起安装。聊天直接使用你选中的开放平台 API Key，按 DeepSeek 的 API 计价规则结算。[官方聊天 Demo](https://github.com/deepseek-ai/chat-demo/tree/v0.1.4) · [官方 API 计价](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)

## 登录、充值和密钥

1. 在聊天侧栏右下角点齿轮即可打开设置。点左下角头像可直接进入「DeepSeek 账户」，在「登录 DeepSeek」中完成官方账户验证。官方窗口顶部显示当前域名。[开放平台登录](https://platform.deepseek.com/sign_in)
2. 在「设置 → API 密钥管理」或「DeepSeek 账户 → API 密钥管理」查看当前官方账户的密钥，包括在其他设备上创建的密钥。Android 原生列表逐行显示名称和官网提供的 Tracking ID 或密钥掩码。点「新建密钥」创建。点开某把密钥，可查看平台提供的日期，并使用该密钥支持的重命名或删除操作。DSH 等由官方托管的密钥按平台允许的操作展示。官网提供分页时可翻页。加载中、需要登录或读取失败时都在应用内显示相应状态。[官方密钥管理](https://platform.deepseek.com/api_keys)
3. 在「DeepSeek 账户 → 余额与充值」查看余额，选择 ¥10、¥50、¥100 或输入自定义人民币金额。进入官方收银台后点「填入」可将金额写入可识别的人民币充值输入框，核对账号、官网金额和支付方式后再付款。遇到其他表单时，可复制金额或按官网提供的选项填写。支持的金额和支付方式以官网为准。[官方充值页](https://platform.deepseek.com/top_up)
4. 支付宝收银台发出唤起链接后，应用用原订单打开已安装的支付宝。如果官网只显示付款码，先让完整二维码出现在屏幕上，再点「打开支付宝或微信」。对识别到的支付宝官方 HTTPS 订单码，应用在本机解码并交给支付宝。要核对支付结果，可回到账户页刷新余额或查看官方账单。[官方账单](https://platform.deepseek.com/transactions)
5. 微信 H5 订单可以按官方链接唤起微信。对于微信 Native 扫码单，应用可显示当前订单的大码供另一台设备扫描。要在本机继续，可点「复制网址并打开微信」，在微信中打开官方充值页，核对账号和原订单状态。该操作复制的是充值入口网址。[微信 H5 支付](https://pay.wechatpay.cn/doc/v3/merchant/4012791845) · [微信 Native 支付](https://pay.wechatpay.cn/doc/v3/merchant/4012791874)

「聊天使用的密钥」使用 Android 原生列表和输入框，可添加、验证、选择、改名或从本机移除已有密钥。账户和聊天沿用现有的 Android Keystore 加密存储。撤销官方密钥需在「API 密钥管理」中操作。[首次调用 API](https://api-docs.deepseek.com/zh-cn/)

新建官方密钥后，如果平台已展示这一次签发的结果，可以直接点「保存并用于聊天」。应用将它保存在当前本机账户，并在返回时更新聊天与余额查询用的密钥。Tracking ID 用于定位已有密钥；聊天鉴权需要完整的 API Key。需要查看一次性完整密钥或手动绑定时，可在官方创建结果中复制。官网仅在创建时展示完整值。

官方目录、加载状态和支持的管理表单都使用 Android 控件。数据来自当前登录账户的官方页面。账户验证、查看官网用量或手动核对不受支持的表单时，可在应用内打开带域名的官方页面。

官方余额接口可分币种查询总余额、充值余额和赠金。DeepSeek 的公开 API 文档提供余额查询。账户级密钥管理和充值下单仍通过官方开放平台会话完成。[官方 API 文档](https://api-docs.deepseek.com/zh-cn/api/deepseek-api/) · [官方余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)

## 侧栏、账户和项目

- 在聊天页向右拖动即可跟手拉开侧栏，向左拖动收起。点聊天页右上角可新建对话。
- 在会话的更多菜单中可以置顶、移入或移出项目。项目内置顶的对话排在该项目最前，项目外的置顶对话集中显示，其余未分组对话列在「最近对话」。删除项目会保留其中的对话。
- 点侧栏左下角头像可以修改本机用户名、昵称和圆形头像，也可新建或切换本机账户。昵称默认采用用户名。每个账户有自己的会话、草稿、附件、项目、密钥和回答偏好。原有记录保留在默认账户。头像只在本机裁剪、保存。
- 每个本机账户都可登录对应的官方平台账户。支持多配置的 Android System WebView 可分别保存各账户的官网登录状态。旧版 WebView 共用网页登录时，官方窗口会持续提示核对账号。若选择外部浏览器继续登录或支付，也要在浏览器中核对账号。

## 聊天、上下文和设置

输入框可直接选择 Flash 或 Pro。新会话默认预选 Flash，已有会话分别保存模型选择。重新生成时使用当前模型，续写沿用原回答的模型。Flash 可使用智能搜索、链接阅读与附件问答。Pro 支持文本对话与深度思考。上游 Demo 还提供流式输出、来源引用、语音输入与朗读及消息编辑。[官方聊天指南](https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/) · [DSH 模型选择](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-model-selection/README.md)

输入框内的圆环表示当前分支预计剩余的上下文。点圆环可查看剩余空间、草稿估算，以及整个会话和每次回答的实际用量；重新生成的其他分支也算入会话累计。实际用量采用 API 返回的 token 记录。附件内容等无法可靠估算时，圆环显示「—」，详情会说明原因。[官方模型列表](https://api-docs.deepseek.com/zh-cn/api/list-models/) · [用量字段](https://api-docs.deepseek.com/zh-cn/api/create-message/)

设置首页、DeepSeek 账户、官方密钥目录、本机聊天密钥、余额与充值、外观、字体大小和个性化使用 Android 原生控件。可以切换跟随系统、浅色或深色，调整四档字号，以及为每个账户设置回答风格与默认回答语言。聊天正文由 APK 内置界面渲染。开放平台的身份验证、收款和官方密钥签发由应用内官方页面完成。窄屏会为官网的过宽表格和过高对话框提供滚动，方便核对整行数据和触达表单底部按钮。

## 对话导入与导出

在「设置 → 数据管理」导出当前账户的完整 ZIP，在单个对话的更多菜单中导出该对话。备份含项目、置顶状态及本机已有的附件原件，密钥与登录凭证留在本机。支持导入兼容的 DeepSeek ZIP 和旧版 TokenSeek 备份。

默认选择「合并导入」会保留已有的同 ID 会话。「覆盖恢复」需明确选择，会替换当前本机账户的记录。其他账户的数据保持独立。对话历史保存在手机上。

## 待办

通过 DeepSeek 网页端账户直接聊天、无需开放平台 API Key 的模式已列入待办。当前版本使用开放平台 API 聊天。

## 构建安卓版

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、Node.js 24、npm、Git 和 Bash。设置 `ANDROID_HOME` 或在 `local.properties` 指定 `sdk.dir`。首次构建需联网获取依赖与锁定版本的上游代码。

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

调试安装包生成在 `app/build/outputs/apk/debug/app-debug.apk`，最低支持 Android 8.0。聊天界面需要较新的 Android System WebView。Gradle 会应用 TokenSeek 的补丁，执行 TypeScript 检查，并将聊天资源和上游许可证一起打包。

| 部分 | 源码 |
| --- | --- |
| 聊天页、侧栏、模型与用量 | `web/overlay/src/components/chatInput/`、`web/overlay/src/components/sider/`、`web/overlay/src/services/chat/` |
| 本机账户、钱包与密钥 | `web/overlay/src/components/settings/`、`web/overlay/src/services/profiles.ts`、`app/src/main/java/io/tokenseek/app/NativeChatKeys.java`、`KeyVault.java` |
| Android 原生设置、官方页面和支付 | `app/src/main/java/io/tokenseek/app/NativeSettings.java`、`app/src/main/java/io/tokenseek/app/OfficialPlatformActivity.java`、`app/src/main/java/io/tokenseek/app/PaymentHandoff.java`、`NativePaymentQr.java` |
| Android 原生官方密钥管理 | `OfficialKeyDirectory.java`、`OfficialKeyPanel.java`、`OfficialKeyForm.java`、`OfficialIssuedKey.java`、`app/src/main/res/raw/platform_keys.js`、`app/src/main/res/raw/platform_key_form.js` |
| 官方 Demo 版本、差异及打包 | `tools/build-web.sh`、`web/patches/` |
