# 本地聊天 (LocalChat)

一个简洁的 Windows 本地聊天应用(WPF / .NET 9),可接入任意大模型服务,支持流式输出与深度思考展示。

## 功能

- **多服务商接入**:OpenAI 兼容、Anthropic Messages、Ollama 原生三种接口格式,多模型任意切换
- **深度思考**:思考过程实时直播、结束自动折叠,展示思考耗时
- **Markdown 渲染**:标题、列表、表格、代码块、链接等(流式增量渲染)
- **上下文管理**:用量圆环直观展示 token 占用,一键压缩上下文(自动生成摘要,原消息保留可查看)
- **会话管理**:侧边栏会话列表,右键重命名 / 删除 / 导出 HTTP 数据(含请求与响应原始报文)
- **细节体验**:侧边栏可拖动调宽、消息自由复制、贴底自动跟随滚动、设置本地持久化

## 下载

前往 [Releases](../../releases) 页面下载:

| 文件 | 说明 |
| --- | --- |
| `LocalChat-*-win-x64-portable.zip` | 自包含单文件,解压即用,无需安装运行时 |
| `LocalChat-*-win-x64.zip` | 体积更小,需安装 [.NET 9 桌面运行时](https://dotnet.microsoft.com/download/dotnet/9.0) |

## 本地构建

需要 [.NET 9 SDK](https://dotnet.microsoft.com/download/dotnet/9.0)(或 Visual Studio 2022):

```bash
dotnet build LocalChat/LocalChat.csproj -c Release
dotnet publish LocalChat/LocalChat.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o publish
```

也可以在 GitHub 的 **Actions → Release → Run workflow** 手动触发构建并自动发布 Release。

## 使用

1. 启动后点击右上角 ⚙ 打开「模型服务设置」;
2. 添加服务商(如本地 Ollama),填入 Base URL 与 API 格式,拉取/添加模型并启用;
3. 选择模型,输入消息开始对话;左下角开关控制是否开启深度思考。

> 设置与会话记录保存在 `%APPDATA%\LocalChat\settings.json`。
