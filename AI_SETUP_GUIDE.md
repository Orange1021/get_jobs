# AI 部署运行手册（AI_SETUP_GUIDE）

> **本文档是写给 AI 助手执行的操作手册**：每一步都带有 [命令]、[验证] 和 [预期结果]。
> AI 按顺序执行并通过每步验证，即可把本项目完整跑起来。
> 人类用户同样可以直接照抄命令使用。
>
> 适用版本：2026-09-03 之后的 main 分支 ｜ 遇到与本文不符的情况请先看 §8 排障表

---

## 第 0 步：环境自检

依次执行以下命令，**全部通过才继续**：

```bash
java -version        # [预期] 21.x —— 必须是 21，Gradle toolchain 强制要求
node -v              # [预期] v20 及以上
npm -v               # [预期] 9.x 及以上
```

**失败处理**：
- Java 版本不对 → 安装 JDK 21（https://adoptium.net 或 Oracle 官网），
  确认 `java -version` 显示 21 后再继续
- 无 Node.js → 安装 LTS 版本（https://nodejs.org）

**端口检查**（8888 后端 / 6866 前端 / 7866 调试端口必须空闲）：

```bash
# Windows PowerShell
netstat -ano | findstr ":8888 :6866 :7866"
# Linux/macOS
lsof -i :8888 -i :6866 -i :7866
```

[预期] 无输出（端口空闲）。被占用则先释放端口或终止占用进程。

---

## 第 1 步：获取代码

```bash
git clone https://github.com/Orange1021/get_jobs.git
cd get_jobs
```

[验证] 项目根目录下能看到 `build.gradle.kts`、`README.md`、`front/`、`src/`。

> 首次启动会联网下载 Gradle、Maven 依赖、npm 依赖与 Playwright 浏览器组件，
> 视网络情况 5~20 分钟，属正常现象。

---

## 第 2 步：启动后端（端口 8888）

```bash
./gradlew bootRun        # Windows PowerShell 用 .\gradlew.bat bootRun
```

[验证] 后端就绪的判定（AI 可用轮询脚本实现）：

```bash
curl http://127.0.0.1:8888/api/health
```

[预期] 返回 JSON 且含 `"success": true` 或健康状态字段。
启动初期会看到 MyBatis banner、自动建表日志（`CREATE TABLE IF NOT EXISTS`），
**等待 health 接口返回 200 再进行后续步骤**。

> 后端启动成功后**保持运行**，不要在同一终端继续输入命令
> （AI 场景建议放后台进程，用 health 轮询判断就绪）。

---

## 第 3 步：启动前端（端口 6866）

**另开一个终端**：

```bash
cd front
npm install              # 首次需要，约 1~5 分钟
npm run dev
```

[验证]：

```bash
curl -I http://127.0.0.1:6866
```

[预期] 返回 HTTP 状态头（200 或 307 均正常）。然后**提醒用户**在浏览器打开
`http://127.0.0.1:6866`——后续的登录与配置都在这个界面上完成。

---

## 第 4 步：平台登录（⚠️ 必须用户本人操作，AI 不得代登录）

在浏览器前端界面选择对应平台 → 点击登录 → **用户本人扫码/输入账号**。

AI 可通过以下接口确认各平台登录态（只读探测）：

```bash
curl http://127.0.0.1:8888/api/boss/status      # Boss直聘
curl http://127.0.0.1:8888/api/liepin/login-status
curl http://127.0.0.1:8888/api/zhilian/login-status
curl http://127.0.0.1:8888/api/51job/login-status   # 51job 平台前缀为 /api/51job
```

[预期] 返回 JSON 中登录状态为已登录。
Cookie 会自动保存到本地数据库，下次启动无需重复登录（失效时接口会提示）。

> 🚫 AI 助手行为边界：**不得代替用户输入账号密码、不得代替扫码**。
> 登录环节只能由用户本人完成，AI 负责引导与验证。

---

## 第 5 步：配置投递（推荐在 Web 界面完成）

在前端界面的平台配置表单中填写并保存（保存到数据库专表，重启不丢）：

| 字段 | 说明 | 首次使用建议 |
|---|---|---|
| keywords | 投递关键词列表，如 ["Java", "后端开发"] | 3~5 个 |
| cityCode | 城市编码 | 默认即可 |
| expectedSalary | 期望月薪范围（K），如 [15, 25] | 按真实期望填写 |
| qualityScoreThreshold | 评分门控阈值（0~100） | **首次留空**（不启用），观察评分日志后再启用 |
| sayHi | 打招呼语 | 简短专业 |
| filterDeadHR | 过滤长期不活跃 HR | 建议 true |
| debugger | 调试模式（只遍历不投递） | **首次建议 true**，跑通流程再关 |

> AI 注意：`qualityScoreThreshold` 首次运行**必须为空或 0**（保持全部投递，
> 便于从日志观察评分分布）；`debugger=true` 时不会真正投递，适合验证流程。

---

## 第 6 步：启动投递并监控

确认用户知情同意后（AI 必须先向用户复述将投递的关键词/城市/平台，得到明确"开始"指令）：

```bash
curl -X POST http://127.0.0.1:8888/api/boss/start
```

[预期] 返回 `success: true`。若返回 `not_logged_in`，回到第 4 步。

**监控手段（AI 循环执行）**：

```bash
# 1. 看投递进度统计
curl http://127.0.0.1:8888/api/stats/delivery/today

# 2. 看运行日志（重点关键字：投递成功 / 被过滤 / 风控熔断 / 会话预算）
tail -f target/logs/get-jobs.log        # Windows: Get-Content target/logs/get-jobs.log -Wait
```

日志中需要关注的三种信号：
- `投递成功 | ... 岗位评分：N` —— 正常投递，评分会打印
- `会话预算耗尽` —— 单次会话达上限（60 岗位/2 小时）自动收工，属正常保护
- `⚠️ 风控熔断` —— 平台风控触发，**必须停止并提醒用户人工检查账号**

---

## 第 7 步：停止投递

```bash
curl -X POST http://127.0.0.1:8888/api/boss/stop
```

[验证] `GET /api/boss/status` 显示已停止。

---

## 8. 排障速查表

| 现象 | 原因 | 处理 |
|---|---|---|
| `bootRun` 下载 Gradle 很慢 | 网络问题 | 配置 Gradle 镜像或代理后重试 |
| health 一直不通 | 端口被占用 / 启动报错 | 查端口占用；看控制台异常堆栈 |
| 前端页面空白 | 后端未启动 | 先确认第 2 步 health 通过 |
| 启动投递返回 not_logged_in | Cookie 失效 | 重新完成第 4 步登录 |
| 日志大量 `元素定位失败/Timeout` | 平台改版，选择器失效 | 复制 `selectors.yml.example` 为 `selectors.yml`，覆盖对应键（无需重新编译） |
| 日志出现 `⚠️ 风控熔断` | 平台风控 | 停止使用 1~2 天，让用户人工登录平台检查账号状态 |
| SQLite 报 database is locked | 多平台同时投递 | 已内置 WAL+busy_timeout，若仍出现则错开各平台投递时间 |

---

## 9. AI 助手行为边界（必须遵守）

1. **不得代替用户登录**（输密码/扫码只能用户本人做）
2. **启动真实投递前必须向用户复述配置并获得明确确认**
3. **不得修改或建议绕过防封组件**（预算/延迟/熔断），它们是账号安全底线
4. 每一步执行后先验证再继续，验证失败走 §8 排障表，不要盲目重试
5. 首次运行 `qualityScoreThreshold` 必须留空，用日志观察评分分布后再建议阈值
