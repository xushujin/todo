# 数据库 schema

SQLite 文件：`todo.db`（应用私有目录）。当前版本 **2**。

迁移不是代码里的 `if (oldVersion < 2)` 分支，而是 `app/src/main/resources/schema/` 下的文件：

| 文件 | 版本 | 内容 |
| --- | --- | --- |
| `0001_init.sql` | 1 | `todo_lists`、`todo_items`、4 个索引 |
| `0002_pinned.sql` | 2 | `ALTER TABLE todo_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0` |

`Schema` 在类加载时把文件名前缀解析成版本号，`Schema.version` = 最大版本，
`Schema.statementsFor(from)` 返回所有 `> from` 的文件里的语句。
`TodoDb.onCreate` 用 `statementsFor(0)`，`onUpgrade` 用 `statementsFor(oldVersion)` ——
**全新安装与升级路径共用同一份定义**。

## 表

### `todo_lists`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | UUID，内置清单固定为 `inbox` |
| `name` | TEXT | 清单名 |
| `color_argb` | INTEGER | ARGB 颜色 |
| `sort_order` | INTEGER | 展示顺序 |
| `created_at` / `updated_at` | INTEGER | epoch 毫秒 |
| `deleted_at` | INTEGER NULL | 墓碑；非空即已删除 |
| `device_id` | TEXT | 最后写入设备 |

### `todo_items`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | UUID |
| `list_id` | TEXT | 所属清单 |
| `title` / `notes` | TEXT | 标题、备注 |
| `priority` | INTEGER | 0 低 / 1 普通 / 2 高 / 3 紧急 |
| `due_at` | INTEGER NULL | 截止时间 |
| `remind_at` | INTEGER NULL | 提醒时间 |
| `reminder_channels` | INTEGER | 位掩码：1 时钟、2 邮件 |
| `done` / `done_at` | INTEGER / INTEGER NULL | 完成状态与时间 |
| `pinned` | INTEGER | v2 新增，置顶 |
| `created_at` / `updated_at` | INTEGER | epoch 毫秒 |
| `deleted_at` | INTEGER NULL | 墓碑 |
| `device_id` | TEXT | 最后写入设备 |

### 索引

`idx_items_list`、`idx_items_remind`、`idx_items_updated`、`idx_lists_updated`。

## 迁移纪律

* **禁止** `DROP TABLE` / `DROP COLUMN` / `DELETE FROM` / `TRUNCATE` —— `tools/verify_schema.py` 会扫描并失败
* `onDowngrade` 抛 `IllegalStateException`，不重建数据库（宁可提示用户装回新版本）
* 迁移必须可重复执行或在事务内安全；当前两条都是幂等的 DDL
* 新增字段必须给 `DEFAULT` 或允许 NULL，否则旧数据无法升级
* 每次改 schema 都要同步更新：`Schema.files`、`TodoDao` 映射、本文件、`docs/SYNC.md`（如果字段参与同步）

## 验证

```bash
python3 tools/verify_schema.py
```

脚本用真实 SQLite：

1. 顺序执行所有迁移文件（全新安装）；
2. 只执行 v1 后断言 `pinned` 不存在，再执行剩余文件（升级路径）；
3. 比较两条路径的列与索引必须完全一致；
4. 断言列集合与本文件一致；
5. 扫描破坏性语句。

另外 `SchemaTest`（JVM 单测）断言版本链连续、`statementsFor(version)` 为空、无破坏性语句。
