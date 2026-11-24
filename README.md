# TokenSeek 安卓客户端

TokenSeek 把 DeepSeek 的聊天体验和开放平台账户余额放在同一个安卓应用里。聊天界面基于 DeepSeek 官方开源的 `chat-demo`，手机使用用户自己的官方 API Key 直接调用 DeepSeek。模型用量由 DeepSeek 按开放平台规则计费。[官方聊天 Demo](https://github.com/deepseek-ai/chat-demo) · [官方计价说明](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)

## 第一次使用

1. 打开 TokenSeek 的「钱包」，进入 DeepSeek 官方密钥管理页，登录自己的账户，创建 API Key。如果已有 Key，可以直接绑定。[官方密钥管理](https://platform.deepseek.com/api_keys)
2. 返回 TokenSeek，绑定这把 Key。钱包用同一把 Key 读取余额，聊天也从对应的官方账户计费。Android 使用 Keystore 保护保存在本机的 Key。
3. 点充值，核对官方页面上登录的账号与创建 Key 的账号一致，然后在官方页面选择可用支付方式并付款。[官方充值入口](https://platform.deepseek.com/top_up)
4. 返回钱包查看刷新后的余额，再回到聊天发送消息。已绑定的 Key 会在下次启动时自动使用。

## 钱包怎样显示余额

TokenSeek 调用官方 `GET /user/balance`，按币种分别显示总余额、充值余额和赠金。账户可用状态来自 `is_available`。余额接口返回当前快照，具体交易结果请以官方支付页面为准。[官方余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)

截至 2026 年 11 月 24 日，DeepSeek 的公开接入指南要求用户在开放平台申请 API Key。暂未查到允许独立安卓应用替用户签发官方 Key、选定扣款钱包或创建官方充值订单的通用接口。第一版把这些操作交给用户在官方页面完成。DeepSeek Harness 的免填 Key 登录依赖其账户宿主服务。[首次调用 API](https://api-docs.deepseek.com/zh-cn/) · [官方错误码与充值指引](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/) · [Harness 凭证说明](https://deepseek-harness.github.io/deepseek-harness/reference/subsystems/credentials)

## 聊天功能对标

本版本固定使用 DeepSeek 官方 `chat-demo` v0.1.4。上游提供流式回答、深度思考、智能搜索及来源引用、阅读链接、附件问答、语音输入与朗读、会话管理、消息编辑与重新生成，以及历史记录导入导出。会话存储在本机；官方 App 的云端历史同步需要另行接入。TokenSeek 是独立客户端。[锁定版本的源码](https://github.com/deepseek-ai/chat-demo/tree/v0.1.4) · [官方功能对照](https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/)
