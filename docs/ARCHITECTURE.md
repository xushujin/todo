# 架构

## 1. 分层

```
ui/            Compose 界面、主题、ViewModel（只依赖 domain）
  ├─ theme/    调色板 + MaterialTheme 映射 + 拟态组件
  ├─ components/ 拟态按钮/输入框/图标（全部自绘）
  └─ screens/  Home / Editor / Lists / Settings / Sync / About / Lock
domain/        纯 Kotlin：模型、查询、合并、仓储接口（可在 JVM 单测）
  ├─ model/    TodoItem、TodoList、Priority、TodoQuery、TodoOrdering
  ├─ repo/     TodoRepository 接口 + SQLite 实现
  └─ sync/     SyncDocument（格式）、SnapshotMerge（算法）
data/          平台相关实现
  ├─ db/       手写 SQLiteOpenHelper + DAO + 迁移 SQL 资源
  ├─ git/      四家内容 API（GitHub/Gitee/Gitea/GitLab）、SyncEngine
  ├─ mail/     SMTP 客户端、MIME 构造
  └─ prefs/    SettingsStore（唯一设置源）、Keystore 加密
alarm/         AlarmManager 调度、通知、开机重排、提醒投递
security/      BiometricGate（应用锁）
core/          无 Android 依赖的工具：JSON、Base64、HTTP、时间、ID
```

依赖方向单向向下：`ui → domain ← data`，`core` 被所有层使用。`domain` 不 import `android.*`，
所以合并、查询、文档格式都能在 JVM 上直接测。

## 2. 依赖注入

没有 DI 框架。`AppGraph`（`TodoApp.onCreate` 里创建）按固定顺序构造单例：

```
SettingsStore → TodoDb → TodoDao → SqliteTodoRepository
             → UrlConnectionTransport → SmtpClient / EmailReminderSender
             → Notifier → ReminderScheduler → ReminderDelivery
```

`SyncEngine` 按需构造（因为 provider 类型来自配置）：`AppGraph.syncEngine(config)`。
`TodoViewModel` 由 `viewModelFactory { initializer { … } }` 创建，持有 `AppGraph` 引用。

## 3. 线程模型

* 所有数据库与网络操作在 `Dispatchers.IO`（仓储内部 `withContext`）
* UI 状态用 `StateFlow` + `collectAsStateWithLifecycle()`
* 数据变更通过 `SqliteTodoRepository` 内的 `revision: MutableStateFlow<Long>` 触发重查，
  没有手写观察者，也没有额外的数据总线
* 闹钟接收器用 `goAsync()` + `CoroutineScope(IO)`，在 `finish()` 前完成通知/发信

## 4. 关键决策

| 决策 | 原因 | 代价 |
| --- | --- | --- |
| 手写 SQLite 而不是 Room | "核心能力自研"；避免 KSP；迁移用 SQL 文件表达，能被 Python 真实执行校验 | 需要自己写游标映射与查询 |
| schema 作为资源文件 | 运行时与测试读同一份文件，不会出现"建表"和"迁移"两套定义 | 需要保证文件名版本号连续（已有单测与脚本） |
| 手写 JSON | 只服务两种结构（同步文档、REST 响应），避免序列化框架与反射 | 需要自己处理转义与数字精度 |
| 手写 SMTP | 只为"发一封纯文本提醒"，JavaMail 太重 | TLS 之外的扩展（DKIM、附件）不做 |
| Git 走内容 REST API | 自建 pack 协议成本极高且易错；三家平台都提供内容 API | 裸 git 服务器不支持；单文件 1 MB |
| 单文件全量快照 | 合并逻辑简单、可测、Web 端易实现 | 数据量大时每次全量写 |
| 自绘图标 | 不引图标库，风格统一 | 图标数量有限 |
| 手工导航栈 | 页面少，避免 navigation-compose 依赖与序列化 | 无深层链接、无转场动画 |

## 5. 应用锁

* `MainActivity` 继承 `FragmentActivity`（生物识别提示需要）
* `onCreate` 时若开关开启则锁定；回前台是否重验由 `LockPolicy` + 设置项「锁定时限」决定
  （立即 / 30 秒 / 1 分钟 / 5 分钟 / 15 分钟，默认 1 分钟），短暂切走不会打断正在编辑的内容
* 锁定时只渲染 `LockScreen`，不给下层内容任何曝光；同时加 `FLAG_SECURE`
* 应用内容包在 `rememberSaveableStateHolder().SaveableStateProvider("app")` 里，编辑页的输入用
  `rememberSaveable`，所以锁屏替换内容后，解锁仍回到原页面、草稿不丢（进程被杀也能恢复）
* `BiometricGate` 使用 `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`，无指纹设备可用 PIN/图案/密码
* 未录入任何生物识别/凭证时，`availability()` 返回 `NONE_ENROLLED`，UI 应提示（见已知限制）

## 6. 提醒链路

```
保存任务 → ReminderScheduler.schedule(item)
           ├─ CLOCK → AlarmManager.setAlarmClock（系统时钟可见，Doze 可用）
           └─ EMAIL → setExactAndAllowWhileIdle
                        ↓ 触发
              ReminderReceiver（goAsync）
                        ↓
              ReminderDelivery → Notifier.notifyReminder / EmailReminderSender.send
```

* 无精确闹钟权限时降级为 `setAndAllowWhileIdle`，设置页明确显示"未允许，可能延迟"
* `BootReceiver` 监听 `BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED`，从数据库重排全部未来提醒
* 完成或删除任务会取消对应 PendingIntent（请求码由 `id.hashCode()` 派生，稳定可取消）

## 7. 主题

`TodoPalette` 是一个不可变数据类，包含背景/表面/凹陷/强调色/明暗阴影/语义色。
`SettingsStore.themeId` 是唯一状态源，`MainActivity` 收集后传给 `TodoTheme`，
再通过 `LocalTodoPalette` 提供给所有组件；系统栏明暗也在同一处设置。
新增主题只需在 `Palettes.all` 里加一项（并补两个语言的名称字符串）。
