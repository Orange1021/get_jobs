# Get Jobs【工作无忧】项目接手指南（PROJECT_GUIDE）

> 本文档面向**接手本项目的开发者或 AI 助手**，目标是让你在不询问原作者的情况下，
> 全面理解项目的定位、架构、每个模块的功能、配置方式、测试体系与维护要点。
>
> 最后更新：2026-09-03 ｜ 维护者：赵秦程（Orange1021）
> 上游项目：[loks666/get_jobs](https://github.com/loks666/get_jobs)（本项目为其深度改造版，历史已重写为单仓库）

---

## 1. 项目定位与背景

**一句话定位**：一个基于浏览器自动化（Playwright）的全平台自动投简历系统，
支持 **Boss直聘、前程无忧(51job)、猎聘、智联招聘** 四大平台，带 Web 管理界面。

- **原型**：fork 自 loks666/get_jobs（Selenium/Gradle 自动投递脚本），在其基础上完成了
  Gradle + Spring Boot 3.5.7 + Java 21 的工程化重写、Next.js 管理前端、以及大量原创功能。
- **实际战绩**：2026 年 4 月作者曾靠它完成暑期实习投递（真实有效）。
- **当前方向**（2026-09 大改造后）：从"无脑海投"升级为"**防封 + 精准投递**"——
  预算控制、人类行为模拟、风控熔断、岗位评分门控、跨平台统计。

> ⚠️ 合规提醒：自动投递类工具存在平台协议与封号风险。本项目内置的
> 预算/节流/熔断机制就是为了把风险压到最低，**不要移除这些保护**。

---

## 2. 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Java | 21（Gradle toolchain 强制） |
| 框架 | Spring Boot | 3.5.7 |
| 构建 | Gradle (Kotlin DSL) | wrapper 9.x |
| 浏览器自动化 | Microsoft Playwright | 1.51.0 |
| ORM | MyBatis-Plus | 3.5.9 |
| 数据库 | SQLite | xerial jdbc 3.45.1（文件：`./db/getjobs.db`） |
| 前端 | Next.js + React + Radix UI + Chart.js | 见 `front/package.json` |
| 配置 | jackson-dataformat-yaml + dotenv-java | |
| 测试 | JUnit 5 + Mockito + AssertJ（spring-boot-starter-test） | 50 个测试 |

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────┐
│  front/  Next.js 管理界面（配置/启动/停止/进度/统计）      │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP (localhost:8888)
┌──────────────────────▼──────────────────────────────┐
│  application/  Spring Boot 服务层                     │
│   controller/  REST API（11 个控制器）                │
│   service/     业务服务（含 KeywordDeliveryQuotaService）│
│   entity/      17 个实体（对应 SQLite 表）             │
│   mapper/      MyBatis-Plus Mapper                   │
└──────────────────────┬──────────────────────────────┘
                       │ 调用
┌──────────────────────▼──────────────────────────────┐
│  worker/  投递执行层（核心业务）                        │
│   boss/Job51/liepin/zhilian  四个平台 Worker          │
│   manager/  PlaywrightManager（浏览器生命周期）         │
│   utils/    防封与工具组件（本项目精华，见 §5）           │
└──────────────────────┬──────────────────────────────┘
                       │ Playwright
                 ┌─────▼─────┐
                 │ Chromium   │ → Boss直聘/51job/猎聘/智联
                 └───────────┘
```

- **启动入口**：`src/main/java/com/getjobs/GetJobsApplication.java`
- **前端端口**：8888（后端）；前端 dev/prod 由 `front/start-dev.mjs`、`start-prod.mjs` 编排
- **每个平台 Worker 都是 `@Scope("prototype")`** 的 Spring Bean，
  每次投递会话创建新实例，通过 `@Setter` 注入 `page/config/progressCallback/shouldStopCallback`

---

## 4. 目录结构详解

```
get_jobs-1.0.0/
├── build.gradle.kts            # 构建配置（依赖清单，Java 21 toolchain）
├── selectors.yml.example       # 选择器覆盖示例文档（见 §5.6）
├── selectors.yml               # 【本地】选择器覆盖配置（已 gitignore，用户自建）
├── application.yaml → src/main/resources/
├── db/                         # SQLite 数据库文件（gitignore）
├── doc/                        # 上游遗留文档（更新日志、设计稿等）
├── scripts/                    # 辅助脚本
├── src/main/java/com/getjobs/
│   ├── GetJobsApplication.java # Spring Boot 启动类
│   ├── application/            # 服务层（见 §3）
│   └── worker/
│       ├── boss/Boss.java            # Boss直聘投递（~1400行，核心）
│       ├── boss/Locators.java        # Boss 选择器常量（默认值来源）
│       ├── boss/BossConfig.java      # Boss 配置（含 qualityScoreThreshold）
│       ├── job51/Job51.java          # 前程无忧（批量勾选投递模式）
│       ├── liepin/Liepin.java        # 猎聘（API拦截+卡片点击混合模式）
│       ├── zhilian/ZhiLian.java      # 智联（列表页按钮直接投递）
│       ├── manager/PlaywrightManager.java  # 浏览器多页面生命周期管理
│       ├── service/            # 各平台数据落库服务（JobPlatformService 接口）
│       ├── dto/JobProgressMessage.java  # 进度消息（WebSocket/前端）
│       └── utils/              # ★ 防封与工具组件（本项目精华）
│           ├── HumanDelay.java       # 高斯随机延迟
│           ├── SessionBudget.java    # 会话预算状态机
│           ├── DeliveryPacing.java   # 投递节奏策略（延迟参数集中地）
│           ├── RiskGuard.java        # 风控熔断
│           ├── JobScoreService.java  # 岗位质量评分
│           ├── SelectorRepository.java # 选择器外部覆盖
│           ├── PlaywrightUtil.java   # Playwright 静态工具（sleep等）
│           ├── Bot.java / Job.java / Platform.java / JobUtils.java
│           └── StealthScriptManager.java  # 隐身脚本注入
├── src/test/java/...           # 50 个单元测试（见 §8）
└── front/                      # Next.js 前端（见 §7）
```

---

## 5. 核心模块详解

### 5.1 投递主流程（以 Boss 为例，其他平台结构类似）

```
Boss.execute()                                    ← 每次会话入口
 ├─ 初始化 SessionBudget（60 岗位 / 2 小时）        ← 防封①预算
 ├─ 重置 riskBreakTriggered = false               ← 防封③熔断信号
 └─ for cityCode : 城市列表
     ├─ 检查 riskBreakTriggered / 用户取消
     └─ postJobByCity(cityCode)
         └─ for keyword : 关键词列表
             ├─ 检查取消 / 会话预算耗尽 / 风控熔断
             ├─ 查询当日配额（MAX_DAILY_DELIVERIES_PER_KEYWORD = 10/关键词）
             ├─ navigate 搜索页 + waitForSelector 列表容器
             └─ while (当日配额未满 && 会话预算未耗尽)
                 ├─ ③ RiskGuard 风控探测 → CONFIRMED 即熔断全会话
                 ├─ 卡片滚动加载（触底/连续3次无新卡处理）
                 ├─ 点击卡片 → 等详情面板 → 黑名单过滤(岗位/公司/招聘者/死HR)
                 ├─ ④ JobScoreService 评分门控 → 低于阈值跳过
                 ├─ 点"立即沟通" → 关弹窗 → recordDelivery（配额+预算计数）
                 └─ ② DeliveryPacing.betweenDeliveries() 高斯延迟 5~25s
```

- **黑名单**：存 SQLite（`bossService.getBlackCompanies/Recruiters/Jobs`），
  另有 `updateBlacklistFromChats()` 自动从聊天记录识别拒信并拉黑。
- **调试模式**：`config.getDebugger() == true` 时只遍历不投递。

### 5.2 四平台投递模式差异

| 平台 | 类 | 投递方式 | 特有逻辑 |
|---|---|---|---|
| Boss直聘 | `Boss` | 逐卡片点击 → 右侧详情 → 点"立即沟通" → 关弹窗 | 最完整：风控熔断/评分门控/黑名单/死HR过滤 |
| 51job | `Job51` | **批量模式**：逐个勾选 checkbox → 点批量投递 → 处理成功弹窗 | 已有 checkNeedLogin/checkAccessVerification；评分门控在勾选前逐岗位判断 |
| 猎聘 | `Liepin` | 拦截搜索 API（`searchfront4c`）拿结构化数据 + 页面点"聊一聊" | 接口数据与页面卡片按索引对齐；markDelivered 更新投递状态 |
| 智联 | `ZhiLian` | 列表页直接点 `button.collect-and-apply__btn` | 采集与投递两阶段分离（PageJob 携带 index/jobId/salary） |

> ⚠️ **Job51/Liepin/ZhiLian 尚未接入 RiskGuard 探测**（它们沿用各自的
> checkNeedLogin/checkAccessVerification 等自有检查），评分门控与预算/随机节奏已全部接入。

### 5.3 防封体系（worker/utils/，本项目最重要的资产）

四层防线，全部组件化、可离线单测：

| 组件 | 职责 | 关键参数 |
|---|---|---|
| `SessionBudget` | 单次会话硬上限 | 60 岗位 / 2 小时；`isExhausted()` 供循环检查 |
| `DeliveryPacing` | 投递节奏 | 高斯分布均值 10s、σ=3s、夹取 [5s, 25s]（参数集中在此类） |
| `HumanDelay` | 高斯延迟引擎 | 注入 `RandomGenerator` + `LongConsumer`（睡眠函数），测试时收集延迟不真睡 |
| `RiskGuard` | 风控熔断 | 4 条规则（见下）；探测与规则分离 |

**RiskGuard 熔断规则**（按优先级，CONFIRMED 即终止全会话）：
1. 滑块验证页：URL 含 `safe/verify`
2. 验证码组件：geetest / nc-container / nc_1_wrapper / captcha iframe
3. 风控文本：「环境异常」「访问过于频繁」「安全验证」
4. 登录态失效：会话中途出现登录入口

> 设计要点：**探测失败按"无信号"安全降级**，绝不因探测异常中断投递；
> 熔断通过 `riskBreakTriggered`（volatile）传播到整个会话。

### 5.4 岗位评分门控（JobScoreService）

投递前打分（0~100），**低于 `qualityScoreThreshold` 的岗位跳过**：

```
基础分 40
+ 薪资符合度   0~25（解析薪资文本中位数 vs config.expectedSalary，K/月）
+ 关键词相关性 0~20（完整命中 20 / 分词部分命中 10，支持中英混排拆词）
+ HR 活跃度    0~10（月/天/在线 +10；未知 +5；含"年" 0）
+ 福利加分     0~12（五险一金/双休/年终奖/股票期权/远程/弹性工作/餐补/房补，每项+4）
```

- 薪资解析支持：`15-25K`、`15-25K·14薪`、`300-500元/天`（×21.75 工作日）、`24-36万/年`（÷12）
- **阈值不配置（null/0）= 不启用门控**，行为与改造前完全一致（向后兼容）
- 各平台 Config 均有 `qualityScoreThreshold` 字段（Boss/Liepin/ZhiLian/Job51）

### 5.5 配额与统计

- **`KeywordDeliveryQuotaService`**（SQLite 表 `keyword_daily_quota`，
  UNIQUE(platform, keyword, quota_date)）：
  - 写入：`recordDelivery(platform, keyword)` —— 每次投递成功后各平台调用
  - 查询：`getTodayCount` / `getDailyCounts(date)` / `getRecentCounts(days)` /
    `getDailyTotalsByPlatform(date)`
- **`DeliveryStatsController`**（漏斗数据 API，见 §6）
- **投递记录落库**：各平台采集的岗位数据存入各自的表
  （`BossJobDataEntity` / `Job51Entity` / `LiepinEntity` / `ZhilianJobDataEntity`），
  猎聘/智联带 `deliveryStatus` 字段标记投递状态。

### 5.6 选择器外部覆盖（SelectorRepository）—— 平台改版的应对机制

**痛点**：平台改版 → 选择器失效 → 必须改代码重新编译。
**方案**：投递循环中的关键选择器通过 `SelectorRepository.get(platform, key, default)` 解析：

```
优先级：工作目录 selectors.yml > classpath selectors.yml > 代码内置默认值
```

- 覆盖文件格式（见仓库根目录 `selectors.yml.example`，含全部键名说明）：

```yaml
boss:
  DETAIL_HEADER: "div.job-detail-header-v2"
  CHAT_BUTTON: "a.op-btn-chat-new"
```

- Boss 已接入 8 个键：`JOB_LIST_CONTAINER / JOB_CARDS / JOB_NAME_IN_CARD /
  DETAIL_HEADER / BOSS_INFO_ATTR / HR_ACTIVE_TIME / CHAT_BUTTON / JOB_SALARY`
- 解析失败安全降级为"无覆盖"；`selectors.yml` 已 gitignore（本地文件）
- **扩展方式**：新键 = 在 Boss 里加一行 `selectors.get("boss", "新键", 默认值)`，
  并把键名补进 `selectors.yml.example` 文档

### 5.7 登录与 Cookie

- 各平台支持 Cookie 保存/恢复（`CookieService` + `CookieEntity`，
  API：`POST /api/cookie/save`、各平台 `/save-cookie` / `/cookie`）
- 登录态检测：Boss 用 `isLoginRequired()`（登录按钮出现即失效）；
  风控熔断规则④会在**投递中途**登录态失效时自动停止
- PlaywrightManager 管理四个平台的独立 Page，统一 close

### 5.8 前端（front/）

Next.js 14 + Radix UI + Chart.js：
- 四平台配置表单（关键词/城市/薪资/黑名单等）
- 启动/停止投递按钮，实时进度（`JobProgressMessage` 进度消息）
- 数据看板（Chart.js 图表；统计 API：`/api/stats/delivery/*`）
- 开发：`cd front && npm run dev`；构建产物复制由 `scripts/copy-dist.mjs` 处理

---

## 6. REST API 一览（后端 localhost:8888）

| 分组 | 端点 | 说明 |
|---|---|---|
| 统一健康 | `GET /api/health` | 健康检查 |
| **投递统计** | `GET /api/stats/delivery/today` | 今日全平台总量 + 关键词明细 |
| | `GET /api/stats/delivery/recent?days=7` | 近 N 天按日期分组（最多90） |
| Boss | `POST /api/boss/execute` `POST /api/boss/start` `POST /api/boss/stop` `GET /api/boss/status` `POST /api/boss/logout` | 投递控制 |
| | `GET /api/boss/stats` `GET /api/boss/list` `GET /api/boss/reload` | 数据与黑名单 |
| | `GET/POST /api/boss/config/blacklist` `GET /api/boss/config/options/{type}` | 黑名单与选项 |
| Boss配置 | `GET/POST /api/ai/config` `GET /api/ai/health` `GET /api/ai/chat` | AI 打招呼配置 |
| 51job | `POST /api/51job/login` `GET /api/51job/login-status` `POST /api/51job/start` `POST /api/51job/stop` `POST /api/51job/save-cookie` 等 | 同构 |
| 猎聘 | `/api/liepin/*`（login-status/start/stop/status/config/stats/list/cookie…） | 同构 |
| 智联 | `/api/zhilian/*`（config/login/logout/stats/list/start/stop…） | 同构 |
| 通用 | `GET /api/config/{key}` `POST /api/cookie/save` | 通用配置与 Cookie |

---

## 7. 数据库表（SQLite，./db/getjobs.db）

- **keyword_daily_quota** —— 关键词日投递计数（本项目新增，
  UNIQUE(platform, keyword, quota_date)，统计 API 的数据源）
- boss / 51job / liepin / zhilian 各自的岗位数据表（对应 4 个 JobData Entity）
- 各平台配置表（ConfigEntity×4）、选项表（OptionEntity×4）
- blacklist（黑名单）、cookie（登录态）、ai（AI 配置）

> 建表方式：实体类 + `ensureTable()`/`sql.init`（mode: never，首次运行由代码建表）

---

## 8. 测试体系（50 个单元测试，全部离线）

```
src/test/java/com/getjobs/
├── worker/utils/
│   ├── HumanDelayTest           6 个：区间夹取/均值统计/随机性/参数校验/秒毫秒换算
│   ├── SessionBudgetTest        5 个：数量/时长/组合耗尽、可拨动时钟
│   ├── DeliveryPacingTest       2 个：投递间隔边界与随机性
│   ├── RiskGuardTest            9 个：四条熔断规则/优先级/null安全
│   ├── JobScoreServiceTest     13 个：薪资解析四种格式/评分规则/门控决策
│   └── SelectorRepositoryTest   7 个：三级优先/覆盖/空白回落/YAML解析
└── application/service/
    └── KeywordDeliveryQuotaServiceTest  8 个：SQLite 临时文件库真实跑 SQL
```

**运行**：`./gradlew test`
**设计原则**：
1. 组件一律支持注入随机源/时钟/睡眠函数——**测试不真睡、不碰网、不开浏览器**
2. SQLite 测试用**临时文件库**（`:memory:` 会随连接关闭销毁，而服务逐调用开连接——踩过的坑）
3. 每次改进：改完 → 测 → 测过才提交（本仓库 2026-09-03 起的铁律）

---

## 9. 构建与运行

```bash
# 环境要求：JDK 21（toolchain 强制）、Node.js（前端）
./gradlew test                 # 跑全部测试
./gradlew bootRun              # 启动后端（端口 8888）

# 前端
cd front && npm install
npm run dev                    # 开发模式
npm run build:prod             # 生产构建（产物复制进后端）
```

- 浏览器内核：Playwright 自动管理（`playwright install` 已在首次运行处理）
- 本地敏感文件（已被 gitignore）：`db/`、`cookie.json`、`config.yaml`、`selectors.yml`

---

## 10. 维护指南（给未来的你/AI）

### 10.1 平台改版导致投递失效怎么办？

按顺序排查：
1. **看日志**定位失败的选择器环节（列表容器？详情面板？沟通按钮？）
2. **改 `selectors.yml`** 覆盖对应键（键名见 `selectors.yml.example`）→ 重启即生效，无需编译
3. 若逻辑变了（不只是选择器），改对应平台 Worker 类

### 10.2 平台适配情报来源（"补丁源"巡检）

本项目上游已停滞（2026-01-30 后无更新），适配修复从以下来源获取：
- **boss-agent-cli**（已克隆在 `C:/Users/123/Projects/boss-agent-cli`）：国内 Boss 方向最活跃开源，
  其 `automation/`、`compliance.py`、`match_score.py` 是设计参考；它跟进平台改版的提交就是情报
- **ump45nose/get_jobs、king-l6/get_jobs** 等活 fork：定期看它们的 commit，有选择器修复就搬
- GitHub compare 页面对本 fork 无效（历史已重写为单提交），对比用本地 `_upstream_get_jobs/` 快照

### 10.3 已知遗留事项（低优先级）

- `PlaywrightUtil` 使用静态全局浏览器状态（单会话场景没问题，多会话并发需重构）
- `DeliveryStatsController` 存在未检查的类型转换（功能正确，编译警告级别）
- RiskGuard 探测仅 Boss 接入；51job/猎聘/智联沿用各自原有验证检查（可作为后续统一项）
- `JobUtils` 内有调试用 `main` 方法（无害）
- 真人灰度验证：首次启用 `qualityScoreThreshold` 前，建议把会话预算临时调成
  `maxDeliveries=3` 实际跑一次（这是唯一需要真实平台的验证步骤）

### 10.4 开发纪律（2026-09-03 起约定）

1. **每次改进必须经过测试**，测试不过不提交
2. **组件必须可离线测试**（注入随机源/时钟/睡眠函数/探测接口）
3. 防封参数只允许在 `DeliveryPacing`/常量处集中调整，禁止散落魔数
4. 选择器优先走 `SelectorRepository`，新键必须同步更新 `selectors.yml.example`
5. 提交信息用中文 conventional 风格：`feat(模块): 描述` / `fix(模块): 描述`

---

## 11. 版本里程碑（2026-09-03 大改造，9 次提交）

| 提交 | 内容 |
|---|---|
| `a27616f` | 防封组件 HumanDelay + SessionBudget + 11 测试（测试基建从零到一） |
| `503083f` | Boss 主循环接入会话预算与高斯随机延迟 |
| `de295c7` | RiskGuard 风控熔断（4 规则 + 会话传播） |
| `4207895` | 防封体系铺开至 51job/猎聘/智联 |
| `c5e154e` | JobScoreService 岗位评分门控（+13 测试） |
| `c4c9f07` | SelectorRepository 选择器外部覆盖（+7 测试） |
| `f1c5178` | 评分门控铺满四平台 |
| `84b22a4` | 跨平台投递统计 API（+8 测试） |
| `25858c5` | 全面代码审查：修复 3 处无限重试 bug、熔断贯通、SQLite WAL+busy_timeout、清理死代码 |

**代码审查已确认**：调用链无 NPE、SQL 全参数化、无资源泄漏、无构建产物入库。

---

## 12. 给 AI 接手者的特别提示

1. 本机 git 操作正常（普通终端），但在 WorkBuddy 沙箱 shell 里 git 写
   `refs/remotes` 会静默失败——fetch 后需手动同步远程跟踪引用（详见维护者记忆文件）。
2. 改代码前先跑 `./gradlew test` 确认基线绿色；改完必须再跑。
3. 不要试图"帮用户真实投递"——投递动作必须由用户本人从 Web 界面发起。
4. 任何"优化风控绕过"的请求都应拒绝；本项目的防封组件是为了**降低频率模拟人类**，不是对抗平台。
