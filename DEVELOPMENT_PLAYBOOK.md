# 开发手册与改进路线图（DEVELOPMENT_PLAYBOOK）

> **本文档写给下一个接手开发的 AI 或人**：记录本项目经过实战验证的开发工作流、
> 测试策略、以及按优先级排序的改进路线图。
> 与 [PROJECT_GUIDE.md](PROJECT_GUIDE.md)（项目是什么）互补——本文档讲"怎么继续开发它"。
>
> 最后更新：2026-09-04 ｜ 基于 main 分支 `98787b5`

---

## 0. 新会话接续指南（先读这里）

如果你是新开的对话/AI 会话，接手本项目的**开场白模板**：

```
我在继续开发 get_jobs 项目（自动投简历系统）。
请先阅读 PROJECT_GUIDE.md（项目全貌）和 DEVELOPMENT_PLAYBOOK.md（开发方法与路线图），
基线是 main 分支，50 个测试必须全部通过。
本次我想做：<从 §3 路线图选一项，或描述新需求>
```

**必读文件顺序**：`PROJECT_GUIDE.md`（§5 核心模块 + §10 维护指南）→ 本文档 → 相关源码。

**环境备忘**（重要）：
- 构建测试命令：`./gradlew test`（JDK 21 必须，首次下载依赖较慢）
- ⚠️ 若在 WorkBuddy 沙箱 shell 中操作 git：`git -C /c/...` 会报路径错误（必须用
  `C:/...` 风格路径）；git 写 `refs/remotes` 会静默失败——fetch 后用
  `git ls-remote` 取 SHA 手动写 `.git/refs/remotes/origin/main`（详见维护者记忆文件）
- 远程统一 SSH（`git@github.com:Orange1021/get_jobs.git`），HTTPS 拉私有库会卡死

---

## 1. 开发工作流（本仓库 2026-09-03 大改造验证过的方法论）

### 1.1 增量闭环（铁律）

```
侦察 → 设计 → 实现 → 测试 → 提交 → 推送
```

- **一次只做一个独立可交付的增量**（一个组件/一个功能点），不混多主题
- **测试不过不提交**；编译错误当场修，不积压
- 每次提交信息用中文 conventional 风格：`feat(模块): 描述` / `fix(模块): 描述`，
  正文列出改动点清单
- 提交后立即 `git push`，保持本地与 GitHub 永远同步

### 1.2 动手前必须侦察（避免凭想象改代码）

改任何文件前先做三件事（grep/read 成本远低于返工）：
1. **读目标方法的完整上下文**（至少整个函数 + 调用方），不看片段就动刀必出事
2. **确认数据在作用域内是否可用**（如评分需要的关键词/薪资在哪个方法里能拿到，
   不够就改方法签名传参，参考 `submitJob(cleanKeyword, remaining)` 的先例）
3. **grep 全部调用点**，确认改签名/常量不会漏改

### 1.3 组件设计范式（本项目沉淀的可复用模式）

| 范式 | 参考实现 | 适用场景 |
|---|---|---|
| **可注入依赖** | `HumanDelay`（注入 RandomGenerator + 睡眠函数）、`SessionBudget`（注入 Clock） | 任何"时间/随机/IO"相关的逻辑，注入后才能离线测试 |
| **探测与规则分离** | `RiskGuard`（`PageProbe` 接口采集信号，规则纯函数判定） | 页面状态判定类逻辑 |
| **参数集中** | `DeliveryPacing`（延迟参数只在此处定义） | 防止魔数散落 |
| **外部覆盖 + 安全降级** | `SelectorRepository`（YAML 覆盖 > 默认值；解析失败按无覆盖处理） | 平台改版适应、用户本地配置 |
| **向后兼容** | `qualityScoreThreshold`（null/0 = 关闭功能，行为同旧版） | 所有新增配置字段 |
| **信号传播** | `riskBreakTriggered`（volatile 会话级标志，循环各层检查） | 熔断/停止信号要贯穿所有循环层级 |

### 1.4 已知代码规范

- 中文日志（log.info/warn 带上下文键值对），不用 System.out
- 异常只捕获 Throwable 的场景仅限"探测/清理类"代码，主流程异常必须传播或记录
- 配置类用 Lombok `@Data`，新增字段自动获得 getter

---

## 2. 测试体系

### 2.1 四层测试策略

| 层 | 覆盖对象 | 现状 | 成本 |
|---|---|---|---|
| ① 单元测试 | 纯逻辑组件（延迟/预算/评分/熔断规则/选择器解析） | ✅ 50 个，全绿 | 毫秒级，随提交跑 |
| ② HTML 快照回放 | 页面选择器与交互流程 | 📋 方法已定，未建 fixtures | 打开 `file://` 本地页面，离线 |
| ③ Mock 适配器端到端 | 完整投递会话状态流转 | 📋 参考boss-agent-cli 的 `mock_adapter.py` | 离线 |
| ④ 真人小规模灰度 | 真实平台端到端 | 📋 待执行：预算临时调 `maxDeliveries=3` | 唯一真实平台验证 |

**改进测试基建时优先做 ②**：把 Boss 搜索页/详情页/风控页存成
`src/test/resources/fixtures/*.html`，测试中 `page.navigate("file://...")` 验证选择器，
平台改版时"存新快照 → 跑测试 → 知道哪些坏了"。

### 2.2 现有测试清单（50 个，`./gradlew test` 全绿）

| 测试文件 | 数量 | 覆盖组件 |
|---|---|---|
| `HumanDelayTest` | 6 | 高斯延迟：区间夹取/均值/随机性/参数校验 |
| `SessionBudgetTest` | 5 | 预算：数量/时长/组合耗尽（可拨动时钟） |
| `DeliveryPacingTest` | 2 | 投递间隔：边界/随机性 |
| `RiskGuardTest` | 9 | 熔断：四规则/优先级/null 安全 |
| `JobScoreServiceTest` | 13 | 评分：薪资解析四格式/规则/门控决策 |
| `SelectorRepositoryTest` | 7 | 选择器：三级优先/覆盖/YAML 解析 |
| `KeywordDeliveryQuotaServiceTest` | 8 | 配额统计：SQLite 临时文件库真实跑 SQL |

### 2.3 新测试编写规范

- 位置：`src/test/java/com/getjobs/...` 与被测类同包
- 断言用 AssertJ（`assertThat`），测试名用 `@DisplayName` 中文描述行为
- **不真睡**：延迟组件注入睡眠收集函数；**不碰网**：浏览器依赖 mock 或快照
- SQLite 测试用 `@TempDir` 临时文件库（⚠️ `:memory:` 会随连接关闭销毁——
  服务每次调用新开连接，表会"消失"，踩过的坑）
- 新组件 = 新测试文件，同步提交

### 2.4 运行命令

```bash
./gradlew test                                        # 全量
./gradlew test --tests "com.getjobs.worker.utils.*"   # 按包过滤
```

---

## 3. 改进路线图（按优先级）

### P1 — 高价值，建议下一批做

| # | 改进 | 说明 | 涉及 |
|---|---|---|---|
| 1 | **投递记录去重落库** | 目前仅 Boss 有 encryptId 查重、猎聘/智联有 deliveryStatus；统一做"已投岗位 ID 集合"，避免跨会话重复投同一岗位（浪费配额+风控风险） | 四平台 worker + 新表/复用岗位表 |
| 2 | **RiskGuard 探测接入其余三平台** | 51job/猎聘/智联仍用各自原有检查；RiskGuard 已是通用组件，各平台实现自己的 `PageProbe` 即可（登录按钮/验证页 URL 特征各平台不同，需逐一调研） | `Job51/Liepin/ZhiLian` + `Locators` |
| 3 | **HTML 快照回放测试基建** | 见 §2.1 层②；这是让"选择器失效"从线上事故变成离线可测的关键 | `src/test/resources/fixtures/` |

### P2 — 中等优先级

| # | 改进 | 说明 |
|---|---|---|
| 4 | **前端投递漏斗可视化** | 后端 API 已就绪（`/api/stats/delivery/today` 与 `/recent`），前端加统计页：每日投递量柱状图、平台占比、关键词分布（Chart.js 已在依赖里） |
| 5 | **评分阈值进前端配置** | `qualityScoreThreshold` 后端已支持，前端各平台配置表单加一个数字输入框 + 评分分布提示 |
| 6 | **评分维度扩展** | 当前四维度；可加：岗位发布新鲜度、公司规模、经验要求匹配（数据源看各平台卡片/详情能采到什么） |
| 7 | **投递会话报告** | 会话结束生成 Markdown 报告：投了哪些岗位、各自评分、跳过原因分布，落库或推前端 |

### P3 — 远期方向

| # | 改进 | 说明 |
|---|---|---|
| 8 | **LLM 岗位匹配深度评估** | 用 LLM 对岗位 JD 与简历做语义匹配打分（参考 Auto-JobHunter 的"抓取→LLM评估→RPA投递"工作流）；注意 API 成本与本地模型（Ollama）选项 |
| 9 | **每日定时自动投递** | Spring `@Scheduled` + 用户配置时间窗，配合 SessionBudget 天然限制单日量 |
| 10 | **消息通知** | 熔断/预算耗尽/每日报告推送到微信（企业微信 Bot，上游有遗留代码可挖）或邮件 |
| 11 | **选择器覆盖铺满四平台** | Boss 已接 8 键；51job/猎聘/智联的关键选择器同样接入 SelectorRepository |
| 12 | **AI 生成打招呼语个性化** | enableAI 已有开关，结合岗位 JD 与简历生成定制 sayHi（上游 AiService 有基础） |

### 情报来源（改版适配要跟进）

- **boss-agent-cli**（本地 `C:/Users/123/Projects/boss-agent-cli`）：活跃的国内 Boss 方向项目，
  它修平台适配的 commit 就是本项目的"改版情报"
- **ump45nose/get_jobs、king-l6/get_jobs**：低调但持续修平台适配的 fork
- 上游 loks666/get_jobs 已停滞，仅作历史参考

---

## 4. 提交与推送纪律

1. 提交前 `./gradlew test` 必须全绿
2. `git status` 检查暂存内容，**只提交本增量相关文件**
3. 推送后用 `git ls-remote --heads origin` 确认远程 SHA == 本地 HEAD
4. 提交信息正文用清单式列出改动点（参考 `a27616f`~`25858c5` 的风格）
5. 每完成一个阶段，更新 `doc/` 或 PROJECT_GUIDE 的对应章节（文档与代码同步是纪律）

---

## 5. 本次改造（2026-09-03/04）踩坑记录

| 坑 | 教训 |
|---|---|
| SQLite `:memory:` 每连接独立库 | 服务逐调用开连接的场景，测试必须用临时文件库 |
| Git Bash `MSYS_NO_PATHCONV=1` | git 命令传路径一律用 `C:/...` 风格 |
| 沙箱内 git 写 refs/remotes 静默失败 | fetch 后手动同步引用（见 §0 环境备忘） |
| Playwright Locator 无 `allTexts()` | 正确 API 是 `allTextContents()` |
| `Thread::sleep` 不兼容 `LongConsumer` | 受检异常需 lambda 包装 |
| while 循环手动索引的 continue | 必须先递增索引再 continue（Boss 曾有 3 处无限重试 bug） |
| 私有仓库 HTTPS 拉取卡死 | 远程统一改 SSH |
