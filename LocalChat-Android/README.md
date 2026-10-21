# 本地聊天 (LocalChat) for Android

Windows 桌面版 [LocalChat](../LocalChat) 的原生 Android 实现(Kotlin + Jetpack Compose),支持 Android 11(API 30)及以上。

## 功能

与桌面版一一对应:

- **多服务商接入**:OpenAI 兼容、Anthropic Messages、Ollama 原生三种接口格式,多模型任意切换
- **深度思考**:思考过程实时直播、结束自动折叠,展示思考耗时
- **Markdown 渲染**:标题、列表、表格、代码块、引用、链接等(流式增量渲染,未闭合代码围栏按代码处理)
- **上下文管理**:用量圆环直观展示 token 占用,一键压缩上下文(自动生成摘要,原消息保留可查看)
- **会话管理**:抽屉会话列表,长按重命名 / 删除 / 导出 HTTP 数据(含请求与响应原始报文,经系统"保存文档"写入)
- **细节体验**:贴底自动跟随滚动、上滚暂停跟随、消息自由选中复制、思考开关本地持久化

## 架构

整体逻辑采用设计模式拆分:

| 模式 | 位置 | 说明 |
| --- | --- | --- |
| 策略模式 | `network/ApiHandler` + `OpenAiHandler` / `AnthropicHandler` / `OllamaHandler` | 三种 API 协议各自实现端点、鉴权头、请求体构造与流式分片解析,由 `ApiHandlers.of()` 工厂创建 |
| MVVM + 观察者 | `ui/MainViewModel` + Compose 状态 | `StateFlow` 式快照状态(`mutableStateOf`),流式输出按 revision 增量刷新 |
| 持久化仓库 | `data/SettingsStore` | kotlinx.serialization 序列化,临时文件 + 原子替换写盘 |

- 流式网络层:`network/LlmClient` 基于 OkHttp,SSE(`data:` 行)与 NDJSON(Ollama)统一解析;OpenAI 兼容服务返回 400 时按报错逐步去掉 `reasoning_effort` / `thinking` 自动重试(最多两次);收集端取消协询即中断底层连接。
- Markdown:`markdown/MarkdownParser` 纯逻辑解析(与桌面版逐行对应),`markdown/MarkdownText` 用 Compose 渲染,支持链接点击跳转。

## 本地构建

需要 Android Studio(Koala 或更新,含 AGP 8.7 / JDK 17)。Gradle 发行版与依赖仓库已默认配置国内镜像(腾讯云 / 阿里云),无需额外加速:

1. 用 Android Studio 打开 `LocalChat-Android` 目录(若提示缺少 Gradle Wrapper,选择让 IDE 自动生成,或本机安装 Gradle 8.9+ 后在项目根目录执行 `gradle wrapper`);
2. 等待 Sync 完成,直接 Run 即可。

如需切回官方源,把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 改回 `https://services.gradle.org/distributions/gradle-8.10.2-bin.zip`,并删除 `settings.gradle.kts` 中阿里云镜像行即可。

命令行构建 APK:

```bash
gradle :app:assembleDebug
```

## 使用

1. 首次启动已内置「本地 Ollama」服务商,启动时会自动拉取本机 Ollama 模型列表(仅一次);
2. 点击右上角 ⚙ 打开「模型服务设置」,可添加服务商(如局域网内的 Ollama、OpenAI 兼容网关),填入 Base URL 与 API 格式,拉取/添加模型并启用;
3. 返回聊天页,顶栏选择模型,输入消息开始对话;输入栏左侧开关控制是否开启深度思考。

> 应用已声明允许明文 HTTP(`usesCleartextTraffic`),可直接连接局域网 `http://` 形式的本地服务。
> 设置与会话记录保存在应用私有目录 `files/settings.json`。
