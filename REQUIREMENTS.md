# Requirements

## 1. Runtime Requirements

- OS: Windows 10/11, macOS, Linux
- JDK: `21` (required by Gradle toolchain)
- Node.js: `>= 20` (建议 LTS)
- npm: `>= 9`
- Git: optional (for clone/update)

## 2. Network Requirements

首次启动需要联网下载依赖与浏览器组件：

- Gradle Wrapper: `https://services.gradle.org`
- Maven 依赖: `https://repo.maven.apache.org` (或镜像)
- npm 依赖: `https://registry.npmjs.org`
- Playwright 浏览器运行组件下载
- 招聘平台本身网络可访问

如果公司网络有限制，需要提前配置代理或镜像源。

## 3. System Requirements

- 必须有桌面图形环境（Playwright 以可视化浏览器运行）
- 可用端口：
  - `8888` 后端 API
  - `6866` 前端页面
  - `7866` Playwright 调试端口
- 可写目录权限（项目目录下需要写入日志与数据库）

## 4. Data & Secret Storage

项目默认使用 SQLite：

- 数据库文件：`db/getjobs.db`

数据库内会保存：

- AI 配置（`HOOK_URL / BASE_URL / API_KEY / MODEL`）
- 各平台 Cookie（登录态）
- 投递历史数据

这意味着仓库打包/分享前必须先做脱敏处理，避免泄露。

## 5. First-run Checklist

1. 启动后端。
2. 启动前端。
3. 打开 `http://127.0.0.1:6866/env-config` 填写 AI 配置。
4. 各平台执行扫码登录。
5. 配置关键词、城市、薪资等参数。
6. 验证后端健康接口：`http://127.0.0.1:8888/api/health`

## 6. Start Commands

Windows:

```powershell
.\gradlew.bat build -x test
.\gradlew.bat bootRun --no-daemon
cd front
npm install
npm run dev
```

macOS / Linux:

```bash
./gradlew build -x test
./gradlew bootRun --no-daemon
cd front
npm install
npm run dev
```
