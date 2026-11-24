# TokenSeek 安卓客户端

TokenSeek 把 DeepSeek 的聊天体验和开放平台账户余额放在同一个安卓应用里。聊天界面基于 DeepSeek 官方开源的 `chat-demo`，手机使用用户自己的官方 API Key 直接调用 DeepSeek。DeepSeek 官方安卓助手提供免费聊天。在 TokenSeek 中发送消息走开放平台 API，模型用量由 DeepSeek 按开放平台规则计费。[官方安卓版](https://www.deepseek.com/en/download/) · [官方聊天 Demo](https://github.com/deepseek-ai/chat-demo) · [官方计价说明](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)

## 第一次使用

1. 在侧栏打开「DeepSeek 账户」，进入官方平台。TokenSeek 会在应用内打开 `platform.deepseek.com` 的真实网页。直接在该页面完成登录。[官方开放平台](https://platform.deepseek.com/)
2. 在应用内进入「充值」，核对官方页面显示的账号、金额和支付方式，按页面提示付款。资金进入该账号的 DeepSeek 开放平台余额。[官方充值入口](https://platform.deepseek.com/top_up)
3. 在「API Key」打开官方密钥管理页，为同一账号创建或管理密钥。要将新建的 Key 用于聊天，复制官网显示的 Key，返回本机选用页面，粘贴后点「验证并选用」。已有 Key 可以直接在本机验证。[官方密钥管理](https://platform.deepseek.com/api_keys)
4. 回到账户页查看官方余额并开始聊天。下次启动会继续使用已选中的 Key。这里的余额与聊天消费都对应这把 Key 所属的账号。

登录、充值和官方密钥管理共享应用内的网页会话。官网页面独立于本地聊天窗口，使用设备的 Android System WebView 显示，地址栏标明正在访问的域名。用户可以直接在网页上使用官网提供的账号登录方式。如果选择的身份提供方要求系统浏览器，或支付方式要求唤起微信、支付宝，TokenSeek 会先显示操作入口，由用户决定是否继续。[Google 登录政策](https://developers.google.com/identity/protocols/oauth2/policies)

本机支持给多把聊天密钥命名、切换或移除，使用 Android Keystore 加密保存。取消选用或移除当前 Key 后，可手动选用另一把。要在 DeepSeek 服务器上撤销密钥，请在应用内的官方密钥管理页操作。

## 钱包怎样显示余额

TokenSeek 调用官方 `GET /user/balance`，按币种分别显示总余额、充值余额和赠金。账户可用状态来自 `is_available`。余额接口返回当前快照，具体交易结果请以官方支付页面为准。[官方余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)

截至 2026 年 11 月 24 日，DeepSeek 的公开接入指南要求用户在开放平台申请 API Key。网页登录状态由应用内的官方页面管理。TokenSeek 使用已验证并选用的 Key 查询余额和调用 API。充值、选择支付方式和创建官方 Key 均在 DeepSeek 官方页面完成。[首次调用 API](https://api-docs.deepseek.com/zh-cn/) · [官方错误码与充值指引](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/)

## 聊天功能对标

本版本固定使用 DeepSeek 官方 `chat-demo` v0.1.4。上游提供流式回答、深度思考、智能搜索及来源引用、阅读链接、附件问答、语音输入与朗读、会话管理、消息编辑与重新生成，以及历史记录导入导出。会话存储在本机。官方 App 的云端历史同步需要另行接入。TokenSeek 是独立客户端。[锁定版本的源码](https://github.com/deepseek-ai/chat-demo/tree/v0.1.4) · [官方功能对照](https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/)

## 模型与用量

在聊天输入区直接选择 Flash 或 Pro。首次打开时预选 Flash，已有会话各自保存模型选择。重新生成回答时使用当前选择，续写已有回答时沿用该回答的模型。Flash 支持本客户端的智能搜索、链接阅读和附件问答。Pro 可用于文本对话和深度思考。如果当前分支中已有附件或网页工具结果，界面会说明继续使用 Flash 的原因。[官方模型与能力说明](https://api-docs.deepseek.com/zh-cn/guides/anthropic_api/) · [DSH 会话模型选择](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-model-selection/README.md)

在聊天末尾展开「对话用量」，可看整个会话，也可逐条查看回答。会话累计包含重新生成的各个分支及续写。输入总数包含缓存命中，输出总数包含思考 token。数值来自 DeepSeek 对话接口返回的用量记录。中断、缺少最终记录或从旧版导入的会话，页面会提示可统计的范围。充值及实际扣费以官方账户为准。[Messages API 用量字段](https://api-docs.deepseek.com/zh-cn/api/create-message/) · [DSH 聊天用量展示](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-chat/README.md)

## 构建安卓版

需要 JDK 17、Android SDK Platform 35 与 Build Tools 35.0.0、Node.js 24、npm、Git 和 Bash。安装 Android SDK 后，设置 `ANDROID_HOME` 或在 `local.properties` 指定 `sdk.dir`。首次构建需要联网下载工具和依赖。

```bash
./gradlew assembleDebug
```

调试安装包生成在 `app/build/outputs/apk/debug/app-debug.apk`，最低支持 Android 8.0，聊天页面需要较新的 Android System WebView。Gradle 会取出固定版本的上游代码、应用 TokenSeek 改动、执行 TypeScript 检查，然后将聊天页面和上游许可证一起打包。

| 要修改的部分 | 源码入口 |
| --- | --- |
| 钱包界面和余额请求 | `web/overlay/src/components/settings/Wallet.tsx`、`web/overlay/src/services/api/balance.ts` |
| 账户、官方页面衔接和本机密钥管理 | `web/overlay/src/components/settings/AccountFlow.tsx`、`web/overlay/src/components/settings/AccountKey.tsx`、`app/src/main/java/io/tokenseek/app/OfficialPlatformActivity.java` |
| 官方 Demo 的聊天入口、设置及密钥存储适配 | `web/patches/0001-tokenseek.patch` |
| 设置页面、模型选择、会话用量界面 | `web/overlay/src/components/settings/SettingsPages.tsx`、`web/overlay/src/components/chatInput/ModelChoice.tsx`、`web/overlay/src/components/messages/ConversationUsage.tsx` |
| Android 打开官方充值页、系统返回和本地加密存储 | `app/src/main/java/io/tokenseek/app/` |
| 官方 Demo 版本与前端打包 | `tools/build-web.sh` |
