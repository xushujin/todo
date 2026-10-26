# 验证记录

本文只记录**实际执行过**的验证，以及**明确没有验证**的部分。命令都在仓库根目录执行。

## 1. 环境

| 项目 | 值 |
| --- | --- |
| 机器 | macOS 15.8.1（x86_64） |
| JDK | Temurin 21.0.13（`jlink` 可用；仅有 JBR/JRE 时 `compileDebugJavaWithJavac` 会失败） |
| Android SDK | Platform 36、Build-Tools 36.0.0 |
| Gradle | 8.14.6（wrapper） |
| AGP / Kotlin | 8.13.2 / 2.2.20 |

## 2. 结果总览

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 全量构建 | `./gradlew clean assembleDebug assembleRelease testDebugUnitTest lintDebug` | **BUILD SUCCESSFUL**，输出中 `w:` / `e:` / `warning` 行数为 **0** |
| 单元测试 | 同上 | **109 个用例 / 13 个测试类，0 失败 0 跳过** |
| Android Lint | `./gradlew lintDebug` | **0 issue**（`app/build/reports/lint-results-debug.xml` 为空） |
| 迁移校验 | `python3 tools/verify_schema.py` | **passed**（2 个迁移文件、列与索引一致、无破坏性语句） |
| Web 逻辑 | `node tools/web_smoke_test.mjs http://127.0.0.1:8765` | **21 项检查全部通过**（含 Gitee 分支） |
| Debug APK | `assembleDebug` | 29 MB，`app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `assembleRelease`（本地 keystore 通道） | 2.9 MB，`apksigner verify` 通过，`zipalign -c 4` 通过 |

Release 签名证书（本机持久密钥，位于仓库之外 `~/keys/todo-release.jks`，口令在 git-ignored 的
`keystore.properties`）：

```
Signer #1 certificate DN: CN=Todo, OU=Mobile, O=Todo, L=Shanghai, ST=Shanghai, C=CN
Signer #1 certificate SHA-256: 720a817308c38d9fa48da8f4d04e6f478eaca78e6d6ef98eb46d5c437549c181
minSdk 30 / targetSdk 36 / debuggable false（apkanalyzer 实测）
```

该 APK 只用于本机安装与验证，未发布到任何公开渠道。

## 3. 真实格式 / 协议验证（不是"编译通过"）

| 对象 | 方式 | 证据 |
| --- | --- | --- |
| GitHub Contents 响应 | 真实结构样本文件 `app/src/test/resources/fixtures/github-contents-response.json`（含每 60 字符换行的 base64、`_links`、`sha`） | `JsonValueTest.parsesRealGithubContentsResponse` 断言 `size` 与解码后字节数一致、内容与 `todos-document.json` 相同 |
| GitLab 文件响应 | 样本 `gitlab-file-response.json`（`content` + `last_commit_id` + `blob_id`） | `JsonValueTest.parsesRealGitlabFileResponse` |
| 同步文档 | 样本 `todos-document.json`（含中文标题、两种提醒通道、置顶、已完成） | `SyncDocumentTest.decodesDocumentWrittenByTheWebClient` |
| Base64 | RFC 4648 向量（`Man`/`Ma`/`M`/`sure.`）+ 带换行 + 非 ASCII 往返 | `JsonValueTest.base64MatchesRfc4648Vectors`、`base64IgnoresLineBreaksAndRoundTripsUnicode` |
| HTTP 传输 | 真实 `ServerSocket` 实现的本地 HTTP 服务器，验证 GET/PUT、请求头、响应体、404 错误流 | `HttpTransportTest`（4 个用例） |
| SMTP 协议 | 真实 `ServerSocket` 实现的 SMTP 服务器，逐条校验 `EHLO → AUTH LOGIN → MAIL FROM → RCPT TO → DATA → QUIT`，并解析收到的 MIME | `SmtpClientTest.sendsMessageThroughARealSmtpConversation` |
| SMTP 失败路径 | 服务器返回 `535` | `SmtpClientTest.surfacesAuthenticationFailures` |
| MIME 头 | RFC 2047 编码主题、CRLF 注入清洗、76 字符折行、RFC 5322 日期格式 | `MimeMessageBuilderTest`（4 个用例） |
| 数据库迁移 | **真实 SQLite**（Python `sqlite3`）执行 `schema/*.sql`，比较"全新安装"与"v1→v2 升级"的列与索引 | `tools/verify_schema.py` 输出：`todo_lists 8 列`、`todo_items 15 列`、4 个索引一致 |
| Web 页面 | Node VM 执行页面真实脚本 + 真实 `fetch` 打 `tools/web_mock_server.py` | 空仓库首写、schema 字段、重新加载、过期令牌触发合并重试、完成/删除写墓碑；Gitee：`POST` 新建、数组响应解析、`PUT` 更新 |
| Gitee 接口契约 | 对照官方 OpenAPI 描述 `https://gitee.com/api/v5/swagger_doc.json` | 路径 `/api/v5/repos/{owner}/{repo}/contents/{path}`、`access_token` 查询参数、创建 `POST` / 更新 `PUT` 且 `sha` 必填、返回 `content.sha` |

## 4. 单元测试清单（109 用例）

| 测试类 | 覆盖 |
| --- | --- |
| `JsonValueTest` | JSON 解析/写出、转义、长整型精度、错误输入、Base64 向量与换行 |
| `SnapshotMergeTest` | LWW、deviceId 决胜、墓碑删除、冲突计数、幂等、可交换、稳定排序 |
| `SyncDocumentTest` | 文档往返、Web 文档解码、未来 schema 拒绝、非法结构、缺省字段、编码稳定性 |
| `TodoQueryTest` | 过滤（今天/紧急/清单/已完成）、排序（置顶优先、无截止时间靠后）、时间工具 |
| `SchemaTest` | 版本链连续、升级只跑增量、无破坏性语句、迁移文件可读 |
| `SyncConfigTest` | https 强制、必填项、路径穿越拒绝、provider 默认值、克隆地址/`owner/repo` 归一化、token 脱敏 |
| `MimeMessageBuilderTest` | 主题编码、头注入、base64 折行、日期格式 |
| `SmtpClientTest` | 真实 SMTP 会话、无认证路径、535 → `SmtpAuthException`（提示用授权码）、配置归一化（去空格 / From 默认用户名）、用户名必须是完整邮箱 |
| `HttpTransportTest` | 真实 socket HTTP、方法/请求体、404、拒绝明文 HTTP |
| `GitContentStoreTest` | GitHub/Gitee/Gitea/GitLab 的 URL、鉴权方式、base64 载荷、`sha`/`last_commit_id`、409/400 冲突、404、1 MB 限制、Gitee 数组响应、0 字节空文件、默认分支查询、错误消息脱敏 |
| `StringResourceConsistencyTest` | 两套语言键一致、占位符一致、带格式符的字符串调用时必须传参（防 `%1$s` 直接显示到界面） |
| `LockPolicyTest` | 锁定时限：立即、宽限期内不锁、到点锁、无基线时锁 |
| `SyncEngineTest` | 首次推送、纯拉取、无变化不写、冲突后重读重试、超过重试上限、非法 schema、非法配置、空文件当空文档、分支不存在时用默认分支解释 404 |

## 5. 明确**没有**验证的部分

以下内容没有在真实环境跑过，代码正确性仅来自编译、静态阅读与上述单测，请勿当作"已通过"：

1. **Android UI 渲染**：没有在真机或模拟器上安装运行，没有截图。拟态阴影、5 套主题、各页面布局、输入法避让、
   深色主题下的对比度均未经视觉确认。
2. **生物识别解锁**：`BiometricGate` 需要真实设备录入指纹/凭证；未验证系统对话框、取消/失败回调、
   `FLAG_SECURE` 效果，以及"设备无生物识别时不锁定"的分支（逻辑已写，未实机执行）。
3. **真实 Git 仓库**：没有对 github.com / gitee.com / 自建 Gitea / GitLab 发起真实请求（无令牌、无外网账号）。
   四家平台的 URL/头/载荷是按官方文档 + 样本构造的，协议细节（例如 Gitee 的 `access_token` 走查询参数
   是否对 POST/PUT 同样生效、Gitea 对 `Bearer` 的兼容性、GitLab 400 的具体触发条件）未经真实服务确认。
4. **SMTP 真实投递**：只验证了明文 SMTP 会话；`STARTTLS` 与隐式 TLS（465）的握手、真实服务商认证、
   中文主题在邮件客户端中的显示，均未验证。
5. **闹钟与通知**：`setAlarmClock` 的实际触发时间、Doze 表现、开机重排、通知渠道与点击跳转，未在设备上验证。
6. **Android Keystore 加密**：`KeystoreSecretCipher` 依赖真实 Android Keystore，JVM 单测无法覆盖。
7. **CI 工作流**：`.github/workflows/*.yml` 未在 GitHub 上运行过（本地无远端仓库），YAML 仅人工检查；
   secrets 名称、`apksigner` 路径、tag 触发逻辑需要在真实仓库首跑时确认。
8. **Release APK 运行**：只验证了"能构建、能签名、能对齐"，没有安装到设备运行（R8 混淆后的运行时行为未验证）。
9. **Web 页面视觉**：只做了脚本逻辑冒烟测试，没有浏览器渲染/交互截图；未验证 Safari 兼容性与移动端布局。
10. **多设备并发**：合并算法有单测，但没有两台真实设备同时同步的端到端测试。

## 6. 如何补验（建议顺序）

```bash
# 1) 真机/模拟器
./gradlew installDebug
adb shell am start -n app.todo.debug/app.todo.MainActivity
adb exec-out screencap -p > /tmp/todo.png        # 目视检查拟态与主题

# 2) 闹钟与通知
adb shell dumpsys alarm | grep -A3 app.todo

# 3) 真实仓库
#    在 App 内填入 GitHub fine-grained token（Contents: Read and write）后点"测试连接"

# 4) 邮件
#    设置页填写 SMTP 后点"发送测试邮件"，检查收件箱

# 5) CI
git remote add origin <your repo> && git push -u origin main
git tag v1.0.0 && git push origin v1.0.0
```

## 7. 本次验证中发现并修复的问题（留痕）

| 问题 | 处理 |
| --- | --- |
| 最新 AndroidX 要求 AGP 9.1 / compileSdk 37 | 固定到 AGP 8.13.2 + compileSdk 36 能支持的最新依赖，并在 dependabot 中禁止 AGP 大版本自动升级 |
| Kotlin 2.4.21 触发 R8 "kotlin metadata" 警告 | 降到 2.2.20，release 构建 0 警告 |
| `JsonValue.asLong()` 与 `Num.asLong()` 互相递归导致 StackOverflow | `Num` 改为 `toLongOrNull()`，由单测覆盖 |
| 设备无生物识别时开启应用锁会把用户锁死 | 仅在 `availability() == AVAILABLE` 时锁定，设置页开关同步禁用 |
| `mipmap-anydpi` 不被 AAPT2 接受 | 保留 `mipmap-anydpi-v26`，用 `app/lint.xml` 忽略 `ObsoleteSdkInt` |
| Lint 43 条告警 | 全部处理：删除未用字符串、KTX 替换、API 常量内联、参数顺序、locale 不静态缓存等，最终 0 issue |
| 全宽按钮文字靠左（未居中） | `NeumorphicButton` 的内容盒默认 TopStart；补 `contentAlignment = Center` |
| 编辑页出现「已完成」开关 | 该开关误用了「显示已完成」的文案；完成状态只由列表的勾选切换，编辑页移除该项并保留原状态 |
| 键盘遮住输入框 / 保存后没有反馈 | 清单加 `adjustResize` + 三个滚动页加 `imePadding()`；保存时按钮变「保存中…」并禁用，成功后 Toast「已保存」 |
| 163 邮箱 535 认证失败只回一句英文 | 配置归一化（去空格、From 默认用户名）、用户名必须是完整地址、535/534 单独抛 `SmtpAuthException`，UI 显示中文指引 + 设置页常驻授权码提示 |
| 同步页服务商列表最后一个 chip 被挤压 | 4 个服务商用不换行的 `Row` 在窄屏放不下；改为 `ChipFlow`（FlowRow），顺带把优先级、排序、SMTP 加密、保存/测试按钮行一并改成可换行 |
| Gitee 的 0 字节文件返回 200 空响应体/`[]`，报"Unexpected response" | 空响应视为空文档，改用父目录列表取 `sha` 后再写；新增 3 个单测（空 body、空数组、端到端 PUT 带 sha） |
| 仓库字段填克隆地址导致 404 | `SyncConfig.normalized()` 解析 URL / `owner/repo` / 去掉 `.git`，校验拒绝把 URL 当仓库名 |
| 0 字节的 `todos.json` 被报"文件过大" | `size == 0` 视为空文档；`size` 缺失且 `encoding:"none"` 才按过大处理 |
| 错误提示里带 `access_token` | 所有错误消息经 `redactSecrets` 打码（截图/日志不再泄露令牌） |
| 分支填错只给一个 404 | 文件缺失时查询仓库默认分支，提示"默认分支是 master"并可一键切换 |
| 测试成功提示排在旧错误下面 | 反馈区只保留最近一次操作，测试连接前清空旧的同步结果；错误按类型显示一句人话 + 小字详情 |
| strings.xml 撇号未转义导致 AAPT 构建失败 | 转义 `'` 并加入 `StringResourceConsistencyTest.apostrophesAreEscaped` |
| 同步页「上次同步」后面显示 `%1$s` | `sync_last` 字符串带占位符却未传参；改为纯标签 + 独立时间值，并新增 `StringResourceConsistencyTest` 双向扫描（已用"故意改回 bug"验证该测试确实会失败） |
| 每次切应用都要求指纹、且未保存的编辑页丢失 | 增加「锁定时限」（默认 1 分钟）与 `LockPolicy`；应用内容包进 `SaveableStateHolder`，编辑页改用 `rememberSaveable` 并加初始化守卫，解锁后回到原页面、草稿保留 |
