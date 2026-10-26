# 同步协议（`data/todos.json`）

同步单位是仓库里的**一个 JSON 文件**，而不是 git 对象。客户端读取 → 合并 → 写回，写入时携带远端版本令牌，
版本不匹配就重新读取再合并，因此不会静默覆盖另一台设备的提交。

## 1. 文件格式

```json
{
  "schemaVersion": 1,
  "generatedAt": 1759999999000,
  "writer": "3f9c2a7b1d40",
  "lists": [
    {
      "id": "inbox",
      "name": "Inbox",
      "color": 4283485140,
      "sortOrder": 0,
      "createdAt": 1759999999000,
      "updatedAt": 1759999999000,
      "deletedAt": null,
      "deviceId": "3f9c2a7b1d40"
    }
  ],
  "items": [
    {
      "id": "8b1f…",
      "listId": "inbox",
      "title": "Buy milk",
      "notes": "",
      "priority": "high",
      "dueAt": 1760000000000,
      "remindAt": null,
      "channels": 1,
      "pinned": false,
      "done": false,
      "doneAt": null,
      "createdAt": 1759999999000,
      "updatedAt": 1759999999000,
      "deletedAt": null,
      "deviceId": "3f9c2a7b1d40"
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `schemaVersion` | 目前为 `1`。读到更大的版本会直接报错，不会丢弃不认识的字段 |
| `generatedAt` / `writer` | 生成时间与写入设备，仅用于排查，不参与合并 |
| `lists[].color` | ARGB，按无符号 32 位整数写出（`0xFF4C5BD4` → `4283485140`） |
| `items[].priority` | `low` / `normal` / `high` / `urgent` |
| `items[].channels` | 位掩码：`1` = 时钟，`2` = 邮件，`3` = 两者 |
| `deletedAt` | 墓碑时间戳。**删除不是移除记录**，而是把 `deletedAt` 设为删除时刻并把 `updatedAt` 一起推进 |
| `deviceId` | 每台设备稳定 ID，用于时间戳相同时的确定性决胜 |

时间全部是 epoch 毫秒（UTC），客户端按本地时区展示。

## 2. 合并规则（`domain/sync/SnapshotMerge.kt`）

对 `lists` 和 `items` 分别按 `id` 做全量并集：

1. 只有一侧有 → 直接采用（计入 `pulled` 或 `pushed`）。
2. 两侧都有：
   * 内容相同（忽略 `updatedAt` / `deviceId`）→ 取较新的那条记录；
   * 内容不同 → `updatedAt` 大的胜；相等时 `deviceId` 字典序大的胜；
3. **冲突**：仅当两侧都在"上次成功同步时间"之后改过同一条记录且内容不同时，计入 `conflicts`，
   胜者仍由第 2 步决定。首次同步（无 `lastSyncAt`）不报冲突，只做 LWW。
4. 结果按 `id` 排序，保证同一份逻辑快照总是编码成同样的字节。

性质（有单测覆盖）：**可交换**（A 合 B == B 合 A）、**幂等**（合完再合不变）、**删除不会被旧编辑复活**。

## 3. 读写流程（`data/git/SyncEngine.kt`）

```
local  = repository.snapshot()          // 含墓碑
remote = store.read(config)             // 不存在则为空快照
merged = merge(local, remote, lastSyncAt)
if (merged == remote) { 应用到本地; 结束 }        // 没有本地变更
try  { store.write(merged, expectedRevision = remote.revision) }
catch (conflict) { 重新 read → 重新 merge → 重试，最多 3 次 }
应用到本地
```

`SyncReport` 返回 `pulled / pushed / conflicts / revision / syncedAt / pushedToRemote`，
同步页据此展示结果；`syncedAt` 会被写回 `SettingsStore.lastSyncAt`，作为下次的冲突基线。

## 4. 三家平台的差异（`data/git/*ContentStore.kt`）

| | GitHub | Gitee | Gitea / Forgejo | GitLab |
| --- | --- | --- | --- | --- |
| 读取 | `GET /repos/{o}/{r}/contents/{path}?ref=` | `GET /api/v5/repos/{o}/{r}/contents/{path}?ref=&access_token=` | `GET /api/v1/repos/{o}/{r}/contents/{path}?ref=` | `GET /api/v4/projects/{o%2Fr}/repository/files/{path}?ref=` |
| 写入 | `PUT` 同一 URL | 新建 `POST` / 更新 `PUT` | `PUT` 同一 URL | 新建 `POST` / 更新 `PUT` |
| 鉴权 | `Authorization: Bearer <pat>` | `access_token` 查询参数（body 里也带一份） | `Authorization: token <pat>` | `PRIVATE-TOKEN: <pat>` |
| 版本令牌 | `sha`（blob） | `sha`（blob） | `sha`（blob） | `last_commit_id` |
| 冲突状态码 | 409 | 400 | 409 | 400 |

* 内容一律 base64（GitHub 返回的 base64 每 60 字符带换行，解码器会忽略空白）
* 新建文件时**不发送** `sha` / `last_commit_id` 字段
* 单文件上限：Contents API 约 1 MB，超出会返回明确错误而不是静默截断
* Gitee 的读取接口在文档里标注返回数组，解析器同时接受对象与数组，并按 `path` 选中目标文件
* Gitee 仓库默认分支常见为 `master`，配置时按实际分支填写

## 5. Web 页面

`web/index.html` 写同一份格式。它不是"读-改-写"地覆盖，而是：

1. 记住最后一次读到的 `base`；
2. 用户在页面上编辑得到 `local`；
3. 写入遇到冲突 → 重新读 `remote` → 对每条记录做三方合并：
   仅本地改过取本地、仅远端改过取远端、两侧都改过按 `updatedAt`/`deviceId` 决胜；
4. 用新的版本令牌重试（最多 3 次）。

这与 Android 端的 LWW 规则一致，因此两端交替编辑不会互相回退。

## 6. 不支持的情况（明确说明）

* 不实现 git 的 pack 协议 / 裸仓库传输，只走平台的内容 REST API。
* 不支持二进制附件、多文件分片、历史版本回溯。
* 时钟提醒与邮件提醒是**本机**行为，不会同步"已提醒"状态；多设备会各自提醒。
