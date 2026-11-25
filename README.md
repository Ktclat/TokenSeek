# TokenSeek

TokenSeek 是面向 DeepSeek 开放平台的独立 Android 客户端。当前原生版 **0.10.2** 支持 Android 8.0 及以上版本。用户可连接自己的 API 密钥，使用 Flash 或 Pro 对话、按项目整理记录，并查看每次回复的 token 用量和账户余额。

## 获取原生版

| 任务 | 入口 |
| --- | --- |
| 查看原生版源码、安装步骤和功能说明 | [原生版使用指南](https://github.com/Ktclat/TokenSeek/blob/feature/android-native/README.md) |
| 在 GitHub 构建调试 APK | [Build Android APK](https://github.com/Ktclat/TokenSeek/actions/workflows/android-debug.yml) |
| 从旧版迁移本机数据 | [升级和迁移指南](https://github.com/Ktclat/TokenSeek/blob/feature/android-native/migration/README.md) |

在 **Actions → Build Android APK → Run workflow** 手动运行。工作流版本保留 `main`，源码默认使用 `feature/android-native`。在运行成功后从 **Artifacts** 下载 APK 与 SHA-256 校验文件。托管运行器使用临时调试签名，有本机数据的用户请在安装前查看使用指南中的备份与升级说明。

## 分支说明

原生版在 `feature/android-native` 开发。`main` 中的应用代码仍是早期版本。要在本地构建原生版，请先切换到原生分支，再按使用指南配置 JDK 和 Android SDK。[早期版本说明](https://github.com/Ktclat/TokenSeek/blob/5f66f100b3c93c1d4e36fc2e4266ca21eea6c985/README.md)

## 开放平台接入状态

DeepSeek 官网页面负责平台登录，TokenSeek 使用单独验证的 API 密钥调用聊天与余额接口。当前原生用量页可查看已有官方报表。账户全量用量在线刷新、平台密钥的创建与撤销、官方充值和支付仍待接入。[原生版功能说明](https://github.com/Ktclat/TokenSeek/blob/feature/android-native/README.md)
