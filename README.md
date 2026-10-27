# Todo

一个原生 Android 待办事项应用：Kotlin + Jetpack Compose，拟态（Neumorphism）界面，多套配色主题，
时钟闹钟 + 邮件双通道提醒，生物识别应用锁，以及通过**自建 Git 仓库**完成的双向同步。
仓库里还带一个纯静态 Web 页面，读取同一个 `data/todos.json` 做展示与操作。

> 应用英文名：**Todo** · 包名：`app.todo` · 版本：`1.0.2` · 支持 Android 11（API 30）– Android 16（API 36）

---

## 1. 需求对照

| 需求 | 实现位置 |
| --- | --- |
| 待办事项可设置不同类型列表 | `TodoList` + 清单管理页 `ui/screens/ListsScreen.kt`，内置 Inbox |
| 紧急程度 | `Priority`（低/普通/高/紧急），首页可按紧急程度排序与筛选 |
| 手机时钟提醒 | `alarm/ReminderScheduler.kt` 使用 `AlarmManager.setAlarmClock`，系统时钟可见，Doze 下仍触发 |
| 邮件提醒 | 自研 SMTP 客户端 `data/mail/SmtpClient.kt`（EHLO / STARTTLS / AUTH LOGIN / AUTH PLAIN / DATA），MIME 由 `MimeMessageBuilder.kt` 生成 |
| 配置自建 Git 仓库双向同步 | `data/git/`：GitHub / Gitee / Gitea(Forgejo) / GitLab 四种内容 API + `SyncEngine` 读-合并-写（乐观并发） |
| 自己的 Web 页面读取该仓库 | `web/index.html`（零依赖单文件，同一份 JSON 格式，三方合并） |
| 生物识别（指纹）解锁 | `security/BiometricGate.kt` + `ui/screens/LockScreen.kt`：冷启动必锁；回前台按「锁定时限」（立即 / 30 秒 / 1 分钟 / 5 分钟 / 15 分钟，默认 1 分钟）决定是否重验；锁定时 `FLAG_SECURE`，解锁后回到原页面并保留未保存草稿 |
| 拟态风格 + 多套配色 + 全局切换 | `ui/theme/Palette.kt`（5 套）、`ui/theme/TodoTheme.kt`、`ui/components/Neumorphic.kt` |
| 不引第三方 UI 库 / DI 框架 | 见下节依赖清单，UI 全部 Compose + 自绘组件，依赖注入由 `AppGraph` 手工完成 |
| 存储方案 + 显式迁移 | 手写 `SQLiteOpenHelper` + `app/src/main/resources/schema/NNNN_*.sql`，无 destructive fallback |
| 关于页 | `ui/screens/AboutScreen.kt`：应用名/版本号/包名/技术栈/支持范围/权限说明/隐私 |
| 双通道签名 | `app/build.gradle.kts` 中 `ReleaseSigning`（`keystore.properties` 或 CI 环境变量） |
| 打 tag 触发 CI 构建并发布 Release | `.github/workflows/release.yml` |
| 零编译警告 | `allWarningsAsErrors = true` + `-Xlint:all -Werror`，实际构建无警告输出 |
| 核心逻辑单测 | `app/src/test/` 共 13 个文件、14 个测试类、113 个用例，覆盖 JSON/Base64/合并/协议/HTTP/SMTP/迁移/查询 |
| 二进制/格式类解析用真实文件验证 | 测试用真实结构的 GitHub/GitLab 响应样本；SMTP 走真实 socket；HTTP 走真实本地服务器；schema 用真实 SQLite 执行 |

---

## 2. 依赖与技术选型

**没有任何第三方 UI 库、没有 DI 框架、没有 JGit、没有 JavaMail。**

```
androidx.core:core-ktx                 1.17.0   Kotlin 扩展（官方）
androidx.activity:activity-compose     1.11.0   单 Activity + setContent
androidx.lifecycle:*                   2.9.4    ViewModel / 生命周期
androidx.fragment:fragment             1.8.9    仅用于 FragmentActivity（生物识别提示需要）
androidx.biometric:biometric           1.1.0    Jetpack 对平台 BiometricPrompt 的封装
androidx.compose:compose-bom            2025.10.00  Compose UI / Foundation / Material 3
org.jetbrains.kotlinx:kotlinx-coroutines 1.10.2 协程
junit:junit                            4.13.2   仅测试
```

**自研的核心能力**（不是"调库"）：

| 能力 | 文件 | 说明 |
| --- | --- | --- |
| JSON 解析/序列化 | `core/json/JsonValue.kt` | 手写递归下降解析器 + 写出器，数字保留原始字面量避免 epoch 毫秒精度丢失 |
| Base64 | `core/codec/Base64Codec.kt` | RFC 4648，忽略换行（GitHub 每 60 字符换行），JVM 可测 |
| HTTP 传输 | `core/net/UrlConnectionTransport.kt` | `HttpURLConnection`，禁止跟随重定向、强制 https |
| Git 内容读写 | `data/git/*ContentStore.kt` | 四家平台的 URL/鉴权/版本令牌差异全部手写 |
| 双向合并 | `domain/sync/SnapshotMerge.kt` | LWW + 墓碑 + deviceId 决胜，可交换、幂等 |
| SQLite 存储 | `data/db/TodoDao.kt` | 手写 SQL 与游标映射，无注解处理器 |
| 迁移 | `data/db/TodoDb.kt` + `resources/schema/*.sql` | 版本即文件，升级只跑增量，禁止破坏性迁移 |
| SMTP / MIME | `data/mail/*` | 手写协议状态机与 RFC 5322/2047 头 |
| 生物识别锁 | `security/BiometricGate.kt` + `MainActivity` | 锁定时机、解锁页、`FLAG_SECURE` 由本应用实现 |

> 说明：`androidx.biometric` 只用于**弹出系统生物识别对话框**。平台 `android.hardware.biometrics.BiometricPrompt`
> 的公开构造函数引用 `androidx.fragment.app.FragmentActivity`，在 `android.jar` 中不可直接编译，
> 因此必须经由 Jetpack 封装。锁的业务逻辑（何时锁、如何降级、截图保护）都是本项目代码。

### 版本矩阵

| 组件 | 版本 |
| --- | --- |
| Gradle | 8.14.6 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.20 |
| Compose BOM | 2025.10.00（Compose UI 1.9.3） |
| compileSdk / targetSdk | 36 |
| minSdk | 30 |
| JDK | 21 |

**为什么不是 AGP 9 / compileSdk 37**：2026 年最新的 AndroidX（Compose 1.12、core 1.19、lifecycle 2.11）
的 AAR 元数据要求 **AGP ≥ 9.1 且 compileSdk ≥ 37**，而 AGP 9 改变了 Kotlin 集成方式，属于工具链级升级。
本项目固定在上表这组"稳定 AGP 8.13 + compileSdk 36 能支持的最新依赖"。`.github/dependabot.yml`
已关闭全部自动更新（`updates: []`）：依赖与 action 版本一律人工升级，并在真实构建里验证，
避免自动 PR 把发版流水线打断。

**为什么 Kotlin 是 2.2.20 而不是更新的版本**：AGP 8.13.2 自带的 R8 无法解析更新 Kotlin 的元数据，
release 构建会刷出 "An error occurred when parsing kotlin metadata" 警告。降到 2.2.20 后
`assembleRelease` 输出 0 警告（见 `docs/VERIFICATION.md`）。升级 AGP 9 时可以一并升级 Kotlin。

---

## 3. 环境要求与快速开始

* JDK **21**（AGP 需要 `jlink`；仅有 JRE 的运行时会在 `compileDebugJavaWithJavac` 阶段失败）
* Android SDK Platform **36** 与 Build-Tools **36.0.0**（或更新）
* 首次构建需要网络下载依赖

```bash
git clone <your-fork> todo && cd todo

# 1. 指向本机 SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # 路径按实际修改

# 2. 构建
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3. 单元测试 / Lint / 迁移校验
./gradlew testDebugUnitTest
./gradlew lintDebug
python3 tools/verify_schema.py
```

`JAVA_HOME` 示例：

```bash
export JAVA_HOME=$HOME/jdk21/Contents/Home     # macOS Temurin 布局
```

---

## 4. 签名：两条通道

`app/build.gradle.kts` 里的 `ReleaseSigning` 是唯一实现，解析顺序：

1. **本地通道**：仓库根目录 `keystore.properties`（已 git-ignore）
   ```properties
   storeFile=/absolute/path/to/todo-release.jks
   storePassword=******
   keyAlias=todo
   keyPassword=******
   ```
   模板见 `keystore.properties.example`。
2. **CI 通道**：环境变量
   ```bash
   TODO_KEYSTORE_PATH=/path/to/release.jks   # 或 TODO_KEYSTORE_BASE64（推荐，CI 用）
   TODO_KEYSTORE_PASSWORD=******
   TODO_KEY_ALIAS=todo
   TODO_KEY_PASSWORD=******
   ```

两条通道产出的 `ReleaseSigning` 完全一致；**都不存在时 release 保持未签名**，不会偷偷用 debug key。

> **本机现状**：仓库根目录已有 git-ignored 的 `keystore.properties`，指向仓库外的
> `~/keys/todo-release.jks`（CN=Todo，RSA 4096）。`dist/` 里的 release APK 就是用它签的。
> **这份 jks 一旦丢失，已安装的用户就无法覆盖升级**，请与 `keystore.properties` 一起备份；
> CI 发版时把同一份 jks base64 后放进 `TODO_KEYSTORE_BASE64`，保持签名一致。
生成密钥：

```bash
keytool -genkeypair -v -keystore ~/keys/todo-release.jks \
  -alias todo -keyalg RSA -keysize 4096 -validity 10000
```

---

## 5. 发版：GitHub Flow + tag

分支模型：`main` 始终可发布；改动走短分支，本地跑完质量门禁后合入 `main`；发布即打 tag。
**推 `main` 不触发任何 Actions**——只有 `v*` 标签会触发 `.github/workflows/release.yml`。

```bash
git switch -c feat/whatever
# ... 提交 ...
./gradlew testDebugUnitTest lintDebug renameReleaseApk   # 本地门禁（见第 11 节）
git switch main && git merge --ff-only feat/whatever
git push origin main                      # 不触发 Actions
git tag v1.0.1
git push origin v1.0.1                    # 仅这一动作触发 release.yml
```

`release.yml` 做的事：

1. `tools/verify_schema.py` + `testDebugUnitTest`；
2. 从 `secrets.TODO_KEYSTORE_BASE64` 解出 keystore（缺失直接失败，避免发出未签名包）；
3. 按 tag 推导版本：`v1.2.3` → `versionName=1.2.3`、`versionCode=10203`
   （`MAJOR*10000 + MINOR*100 + PATCH`，单调递增且与仓库历史/run_number 无关）；
4. `./gradlew renameReleaseApk` 构建签名包，产物命名为 `Todo-<version>.apk`；
5. `apksigner verify --print-certs` + `zipalign -c`；
6. 生成 SHA-256，用 `softprops/action-gh-release` 创建 Release 并附上 APK 与校验文件。

需要的 Repository secrets：`TODO_KEYSTORE_BASE64`、`TODO_KEYSTORE_PASSWORD`、`TODO_KEY_ALIAS`、`TODO_KEY_PASSWORD`。

本地等价发版：

```bash
./gradlew renameReleaseApk -PversionName=1.0.2 -PversionCode=10002
apksigner verify --print-certs app/build/outputs/apk/release/Todo-1.0.2.apk
cp app/build/outputs/apk/release/Todo-1.0.2.apk dist/
```

---

## 6. Web 页面

`web/index.html` 是零依赖单文件，功能：配置仓库 → 读取 `data/todos.json` → 增删改/完成/置顶 → 写回。

```bash
python3 -m http.server 8080 --directory web
# 打开 http://localhost:8080
```

也可以用 GitHub Pages 直接从本仓库的 `/web` 目录发布。**不要用 `file://` 打开**：`fetch` 会因
CORS/Origin 为 `null` 被拒绝。

令牌权限：

* GitHub（fine-grained PAT）：Repository access 选单个仓库，Permissions → Contents: **Read and write**
* Gitee：私人令牌，勾选 `projects`（仓库读写）；仓库默认分支常见为 `master`，按实际填写
* Gitea / Forgejo：`write:repository`
* GitLab：`api`（或 `read_repository` + `write_repository`）

配置与令牌只写进浏览器 `localStorage`，不会发往第三方；仍建议使用"仅单个仓库 + 可随时撤销"的令牌。
写入冲突时页面会重新读取远端，按"逐条三方合并 + 最后修改优先"合并后重试（最多 3 次），不会静默覆盖。

本地联调与冒烟测试（不碰真实仓库）：

```bash
python3 tools/web_mock_server.py --port 8765 &     # 静态站点 + 模拟 Contents API
node tools/web_smoke_test.mjs http://127.0.0.1:8765
```

冒烟测试在 Node VM 里跑页面真实脚本、用真实 `fetch` 打模拟服务，覆盖：空仓库首次写入、
schema 字段、重新加载、过期版本令牌触发"重读 + 三方合并 + 重试"、完成/删除写入墓碑，
以及 Gitee 分支（`POST` 新建、数组响应解析、`PUT` 更新）。

---

## 7. 数据与同步

* 同步文件：仓库里的 `data/todos.json`（路径可配置），格式见 [`docs/SYNC.md`](docs/SYNC.md)
* 单文件、全量快照：`{ schemaVersion, generatedAt, writer, lists[], items[] }`
* 合并规则：`updatedAt` 大者胜；相同则 `deviceId` 字典序大者胜；删除是带 `deletedAt` 的普通更新（墓碑），
  所以"删除"和"编辑"天然按时间比较，不会被旧数据复活
* 并发写：写入时携带远端版本令牌（GitHub / Gitee / Gitea 的 blob `sha`、GitLab 的 `last_commit_id`）；
  版本不匹配返回 409/400 → 重新读取 → 重新合并 → 重试（最多 3 次）
* 冲突计数：两侧都在上次同步后改动过同一条记录时，`SyncReport.conflicts` 会加一并显示在同步页
* 墓碑清理：设置页可清理 30 天前的删除记录（清理前请确认所有设备都已同步）

同步是"文件级"的，不实现 git pack 协议；因此需要目标平台提供内容读写 API（GitHub / Gitee / Gitea / GitLab 均可自建）。

---

## 8. 数据库与迁移

* 库名 `todo.db`，当前 schema 版本 **2**
* 每个版本是 `app/src/main/resources/schema/NNNN_*.sql` 一个文件；**全新安装会按顺序执行全部文件**，
  升级只执行 `> oldVersion` 的文件 —— 只有一套"最终形态"定义
* `onDowngrade` 抛异常而不是重建库；迁移文件里不允许 `DROP TABLE / DROP COLUMN / DELETE FROM`
* 表：`todo_lists`、`todo_items`（含 `deleted_at` 墓碑、`device_id`、`pinned`）
* 校验：`python3 tools/verify_schema.py`（真实 SQLite 执行 + 全新安装/升级路径一致性 + 列与索引断言）

新增字段的步骤：新建 `0003_xxx.sql` → 在 `Schema.files` 里登记 → 同步更新 `TodoDao` 映射与
`docs/SCHEMA.md` → 跑 `verify_schema.py` 与单测。

---

## 9. 主题与拟态

5 套配色（`ui/theme/Palette.kt`）：云白 `cloud`、薄荷 `mint`、暖砂 `sand`、薰衣草 `lavender`、午夜 `midnight`（深色）。

* 主题 ID 存在 `SettingsStore` 的 `StateFlow` 里，`MainActivity` 收集后套一次 `TodoTheme`，
  **全局只有这一处切换入口**；系统栏图标明暗也由同一处设置
* 拟态阴影由 `NeumorphicSurface` 用两层彩色阴影（左上高光 + 右下投影）实现，不使用模糊滤镜，硬件加速下稳定渲染
* 按下反馈四层叠加：阴影压低 + 凹陷底色 + 整体缩放到 0.965 + 强调色淡染；按钮另触发一次轻触觉反馈。
  只靠阴影变化时，在相近底色上几乎看不出来，缩放与染色让"按下"一眼可辨
* 图标是 Canvas 手绘（`ui/components/TodoIcon.kt`），不引入图标库

---

## 10. 权限与隐私（与关于页一致）

| 权限 | 用途 |
| --- | --- |
| `INTERNET`、`ACCESS_NETWORK_STATE` | Git 同步、邮件提醒 |
| `POST_NOTIFICATIONS` | 提醒通知（Android 13+ 运行时申请） |
| `SCHEDULE_EXACT_ALARM` | 精确提醒；未授权时降级为 `setAndAllowWhileIdle` 并在设置页提示 |
| `RECEIVE_BOOT_COMPLETED` | 重启/更新/改时间后重排提醒 |
| `USE_BIOMETRIC` | 应用锁 |
| `VIBRATE`、`WAKE_LOCK` | 提醒投递 |

* 任务数据只存在本地 SQLite；只有配置仓库后才联网
* Git 令牌与 SMTP 密码用 Android Keystore 的 AES-256-GCM 密钥加密后存 SharedPreferences
* `allowBackup="false"`；锁定时窗口加 `FLAG_SECURE`，避免最近任务缩略图泄露内容

---

## 11. 测试与验证

```bash
./gradlew testDebugUnitTest        # 13 个文件 / 14 个测试类 / 113 个用例
python3 tools/verify_schema.py     # 真实 SQLite 执行迁移
node tools/web_smoke_test.mjs      # Web 页面逻辑（需先起 mock server）
./gradlew lintDebug                # Android Lint，当前 0 issue
./gradlew assembleDebug renameReleaseApk   # 产物 Todo-<version>.apk
```

已验证 / 未验证的边界写在 [`docs/VERIFICATION.md`](docs/VERIFICATION.md)，包括"没有在真机或模拟器上跑过 UI"
这类必须说清楚的事。

---

## 12. 目录结构

```
app/src/main/java/app/todo/
├── TodoApp.kt / AppGraph.kt         Application 与手工依赖容器
├── MainActivity.kt                  单 Activity、主题、应用锁、通知跳转
├── core/                            json / codec / net / time / ids
├── domain/
│   ├── model/                       TodoItem、TodoList、Priority、查询与排序
│   ├── repo/                        仓储接口与 SQLite 实现
│   └── sync/                        文档格式与合并算法
├── data/
│   ├── db/                          Schema、SQLiteOpenHelper、DAO
│   ├── git/                         四家内容 API、SyncEngine
│   ├── mail/                        SMTP、MIME、邮件正文
│   └── prefs/                       SettingsStore、Keystore 加密
├── alarm/                           调度、通知、开机重排
├── security/                        BiometricGate
└── ui/                              theme / components / screens / AppRoot / ViewModel
app/src/main/resources/schema/       显式迁移 SQL（运行时与测试共用）
app/src/test/                        单元测试与真实格式样本
web/index.html                       静态 Web 客户端
tools/verify_schema.py               真实 SQLite 迁移校验
.github/workflows/                   release.yml（仅 tag `v*` 触发发版；推 main 不跑任何 Actions）
docs/                                架构、同步协议、验证记录
```

---

## 13. 已知限制

* 邮件提醒在提醒时刻需要网络；进程被系统杀死后无法补发（已 `goAsync` 延长存活，但非保证）
* 同步是文件级快照，单文件上限受平台 Contents API 限制（GitHub 1 MB）
* Web 页面需要宿主支持跨域的内容 API；纯裸 git 服务器（无 REST API）不支持
* 时钟提醒的精确性依赖系统"精确闹钟"权限；被拒绝时只能降级
* 首次同步不做冲突计数（没有上次同步时间作为基线），只做 LWW
