# 参与贡献

欢迎提 Issue 和 PR。开始之前请先读完这一页，能省掉大部分来回。

## 环境要求

- JDK 17
- Windows 10 及以上（采集与 OCR 走 Win32 截屏，功能验证只能在 Windows 上做）

## 本地构建

```bash
./gradlew run          # 开发运行
./gradlew build        # 编译 + 测试（冒烟测试在未配置接口的机器上自动跳过）
./gradlew packageExe   # jpackage 产出 AIChatAssistant.exe + 自带 JRE 的目录
```

## 提交前检查

- [ ] `./gradlew build` 通过
- [ ] 不包含任何密钥、`config.json`、真实聊天截图
- [ ] 不包含真实对话内容、真实联系人信息——测试与文档一律用虚构示例数据
- [ ] 改了采集相关逻辑时，说明在哪个聊天软件的哪个版本上验证过

## 提交方式

- 一个 PR 只做一件事，改动范围尽量小。
- commit message 一行说清意图（中文或英文均可）。
- 行为有取舍的大改动（采集方式、隐私相关）请先开 Issue 讨论再动手。
