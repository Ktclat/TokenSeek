# TokenSeek

TokenSeek 是面向 DeepSeek 开放平台的独立 Android 客户端。你可以使用自己的 API 密钥与 Flash、Pro 对话，整理聊天记录，查看每次回复的 token 用量和开放平台余额。聊天、账户、用量和设置均使用 Android 原生控件。

当前版本为 **0.10.2**，支持 **Android 8.0（API 26）及以上版本**。

## 开始使用

1. 安装调试 APK。可以从仓库中一次成功的手动构建下载，也可以自行构建。[构建与验证](#构建与验证)
2. 打开对话侧栏，点左下角头像进入「账户」。在「连接 DeepSeek API」输入已有的开放平台 API 密钥，点「验证并选用」。应用向 DeepSeek 验证密钥后，使用 Android Keystore 保护本机保存的凭据。
3. 回到对话，在输入框选择 Flash 或 Pro 后发送消息。点右上角可新建对话，横向拖动可打开侧栏，查看、置顶对话或将其归入项目。

对话支持流式回复、停止生成和分支。Flash 可处理附件和调用网页工具。语音输入和朗读取决于设备提供的语音服务。[DeepSeek 网页工具文档](https://api-docs.deepseek.com/guides/web_tools/)

「账户 → 登录开放平台」通过浏览器打开 DeepSeek 官方登录页，密码和验证码由官网处理。登录会话保存在浏览器中。应用内聊天和余额查询使用当前选中的 API 密钥，需要单独连接。[DeepSeek 开放平台登录](https://platform.deepseek.com/sign_in) · [API 认证说明](https://api-docs.deepseek.com/zh-cn/api/deepseek-api/)

## 账户、用量与余额

| 入口 | 当前版本可查看或管理的内容 |
| --- | --- |
| 输入框中的用量圆环 | 当前分支的剩余上下文估算，以及整段对话或单条回复的用量。已确认的 token 数量取自 API 响应，缺少最终用量的请求会单独标记。 |
| 账户 → 开放平台用量 | 在原生页面查看已有官方报表，支持用量 ZIP 或 `amount-*.csv`。可按密钥或模型查看明细，含费用记录时按币种汇总。统计范围取决于报表的时段和密钥，可涵盖来自其他设备的请求。 |
| 账户 → 余额 | 通过官方余额接口按币种查询总余额、充值余额和赠送余额，支持手动刷新。 |
| 账户 → 聊天密钥 | 验证、切换、备注或移除当前账户保存在此设备的 API 密钥。 |
| 设置 → 切换账户 | 切换本机账户及其对话、草稿、项目和聊天密钥。可在账户页修改昵称和头像，在设置中调整外观、字号和个性化偏好。 |

尚待接入的开放平台功能包括按时段在线查询账户全部密钥的用量、列出和管理平台上的全部密钥、通过官方订单充值、唤起支付宝或微信支付，以及查询交易记录。这些功能需要适用于 TokenSeek 的官方账户授权与相应接口。[余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/) · [用量字段](https://api-docs.deepseek.com/zh-cn/api/create-message/)

## 备份与升级

- 在**「设置 → 数据管理」**导出当前账户的聊天 ZIP，或从备份恢复。默认合并模式保留同 ID 的已有对话，选择覆盖时会再次确认。API 密钥和登录凭据不包含在备份中。
- 从 **0.10.0 或 0.10.1** 升级时，使用与已安装版本签名相同的 APK 覆盖安装即可保留应用数据。
- 从 **0.9.2 或更早版本** 迁移账户、草稿、附件和设置时，先在原设备覆盖安装同签名的离线迁移工具，点「准备迁移」，再覆盖安装同签名的原生版并确认合并。若只需恢复已导出的聊天 ZIP，可直接在新版导入。[旧版迁移说明](migration/README.md)

## 构建与验证

### 在 GitHub Actions 手动构建

1. 打开仓库的 **Actions → Build Android APK**，选择 **Run workflow**。[打开工作流](https://github.com/Ktclat/TokenSeek/actions/workflows/android-debug.yml)
2. **Use workflow from** 保留 `main`。在 **Source branch, tag, or commit** 指定应用源码，默认 `feature/android-native` 对应当前原生版，也可以填写其他分支、标签或完整提交 SHA。单击 **Run workflow**。
3. 工作流运行 Android Lint、原生界面检查和 Android 14（API 34）模拟器测试，并校验 APK。成功后，在运行详情的 **Artifacts** 下载并解压构建产物，可得到 `TokenSeek-<版本>-debug.apk` 和 `SHA256SUMS`。在解压目录执行 `sha256sum -c SHA256SUMS` 可校验下载的 APK。

GitHub 托管运行器使用临时调试签名，不同次构建的签名可能不同。Android 仅允许同签名安装包覆盖升级。有本机数据时，请先在「设置 → 数据管理」备份对话。如需连续覆盖安装并保留完整应用数据，请使用自己的固定签名构建。

### 本地构建

安装 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0，配置 `ANDROID_HOME` 或在本机 `local.properties` 中设置 `sdk.dir`。在仓库根目录使用随项目提供的 Gradle Wrapper 构建并检查代码。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。连接 Android 8.0 及以上设备或启动模拟器后，可运行 `./gradlew :app:connectedDebugAndroidTest` 执行设备测试。

新增页面请沿用 Android 原生控件。官方登录使用浏览器，其余界面在应用内实现。构建会运行 `verifyNativeScreens` 检查此约定。[原生界面与平台接入约定](AGENTS.md)
