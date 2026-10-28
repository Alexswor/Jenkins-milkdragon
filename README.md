# Jenkins-milkdragon · Jenkins 一键部署助手

当前版本：**0.10.8**。面向 Windows x64 的 JavaFX 桌面工具，将本机接入 Jenkins Agent，识别项目源码并创建独立的前端、后端部署流水线。后端支持 **Java / Spring Boot** 和 **.NET 10 / ASP.NET Core**，前端支持 Node.js 构建的静态站点。

0.10.8 在部署全部成功后显示庆祝弹窗，左上角使用去除背景的黄色大笑角色透明 PNG，保持图片比例。Redis 配置提醒沿用 0.10.6 的逻辑。

## 技术栈与架构

| 层次 | 技术 / 职责 |
| --- | --- |
| 桌面界面 | Java 21+、JavaFX 21、FXML、CSS；三阶段部署向导与服务看板 |
| 应用编排 | MainController、AgentProvisioningService、JobProvisioningService |
| 服务接口 | JDK HttpClient；Jenkins XML/HTTP API、Gerrit REST API |
| JSON 与配置 | Jackson 2.22.2、本机 config.env、生产配置生成与备份 |
| 部署执行 | Jenkins Pipeline、PowerShell、WinSW 2.12.0、IIS |
| 构建发布 | Maven、jlink、jpackage、Windows .NET Framework C# 编译器 |

调用关系：JavaFX UI → 本机检测 / 项目识别 / Job 编排 → Jenkins、Gerrit 和 Windows 服务。Java 与 .NET 使用不同后端模板；前端和后端生成独立 Job。部署先后端、再前端，只有预期 Job 全部 SUCCESS 才提示部署成功。

## 项目框架结构

```text
Jenkins-milkdragon/
├── pom.xml                         # Maven 依赖、Java 版本与 JavaFX 启动配置
├── config.env.example              # 示例配置，不包含真实凭据
├── src/main/
│   ├── java/cn/localops/jda/
│   │   ├── Launcher.java           # 打包后的启动入口
│   │   ├── JenkinsDeployApplication.java
│   │   ├── config/                 # 本机配置、默认值、路径与旧配置导入
│   │   ├── model/                  # 项目、部署结果、运行状态等数据模型
│   │   ├── service/                # Jenkins/Gerrit 客户端、Agent、Pipeline、诊断
│   │   └── ui/                     # 主控制器与向导状态
│   └── resources/
│       ├── ui/                     # main-view.fxml、app.css
│       ├── icons/                  # 程序图标、品牌图与 deployment-success.png
│       ├── pipeline/               # 产物打包与校验 PowerShell 脚本
│       ├── dashboard/              # 服务看板地址读取脚本
│       └── certificates/           # 兼容原部署环境的附加公共根证书
├── packaging/
│   ├── LauncherBootstrap.cs        # Windows 友好启动器
│   ├── winsw/                      # WinSW x64、来源与许可证
│   └── iis/                        # Rewrite/ARR 离线安装包与说明
├── scripts/package-windows.ps1     # 生成便携软件 / EXE 安装程序
└── docs/
    ├── architecture.md             # 架构决策与约束
    └── product-spec.md             # 产品流程与行为规格
```

`target/`、`dist/` 是生成目录，不提交到 Git。此仓库不包含本地账号文件、运行配置、诊断记录或历史版本工作目录。

## 运行与构建要求

- 开发机：Windows x64、JDK 21 或以上（包含 javac、jlink、jpackage）、Maven，首次构建可访问 Maven 依赖源。
- 打包：使用 **Windows PowerShell 5.1**，并安装 Windows .NET Framework C# 编译器；制作 EXE 安装程序还需 WiX。
- 目标服务器：Windows x64；便携包自带 Java Runtime，安装服务 / IIS 时需管理员权限。
- Jenkins：准备 Pipeline、Gerrit Trigger 等模板所需插件，以及 Agent、Job 与构建操作权限；确保源码凭据 `gerrit-http` 可用。
- 构建节点：按项目准备 Git、Node.js/npm、Java 构建工具，或 .NET 10 SDK / Runtime。具体要求取决于项目流水线。

### 1. 配置 Jenkins 与 Gerrit

上传源码已移除原内部版本的硬编码账号密码。**本机旧发布包没有随此次源码上传而重新打包，也不包含在此仓库中。**

Jenkins 的地址、账号和密码可在“连接设置”中填写，或从 `config.env.example` 复制后配置。默认配置路径：

```text
%ProgramData%\JenkinsDeployTool\config.env
```

可通过 `JDA_DATA_DIR` 指定独立数据目录。Jenkins 环境变量只补充尚未保存的字段，不覆盖已有本机配置。

Gerrit 连接信息通过启动进程的环境变量提供（需先配置，再启动程序）：

```powershell
$env:GERRIT_URL = 'https://gerrit.example.com/'
$env:GERRIT_USERNAME = 'your-gerrit-user'
$env:GERRIT_PASSWORD = 'your-gerrit-http-password'

# 可选：首次启动时补充 Jenkins 默认连接信息
$env:JENKINS_URL = 'https://jenkins.example.com/'
$env:JENKINS_USERNAME = 'your-jenkins-user'
$env:JENKINS_PASSWORD = 'your-jenkins-token-or-password'
```

以上均为占位示例，请替换成本机实际设置。不要将真实值提交到仓库。`GERRIT_PASSWORD` 为 Gerrit HTTP API 密码；Jenkins 中 `gerrit-http` 凭据需由管理员另行配置。

原版附加根证书仍随源码保留；使用其他内部 CA 时，应在 `InternalTlsSupport` 中同时更新证书资源及固定 SHA-256，不能通过关闭 TLS 校验解决证书问题。

### 2. 开发运行

在仓库根目录执行：

```powershell
mvn clean package
mvn javafx:run
```

当前源码没有自动测试套件；Maven 构建成功代表编译与打包通过，不等同于目标 Jenkins / Windows 服务部署验收。

### 3. 打包 Windows 软件

从仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1
```

生成便携目录及 ZIP：

```text
dist/Jenkins一键部署助手-0.10.8-windows-amd64/
dist/Jenkins一键部署助手-0.10.8-windows-amd64.zip
```

完整解压 ZIP，运行目录内的 `Jenkins一键部署助手.exe`，不要只复制 EXE。安装了 WiX 后可追加 `-PackageType exe` 生成安装程序。打包脚本会校验 WinSW / IIS 组件哈希及 Microsoft 签名。

## 首次使用

1. 启动软件，确认“连接设置”中的 Jenkins 连接信息；首次安装服务时使用管理员权限。
2. 软件优先识别已有 Windows 服务式 Agent。在线则复用；没有可复用 Agent 时，选择安装磁盘并点击“一键配置本机 Agent”，等待在线。
3. 输入 Gerrit 项目名，选择项目**源码根目录**。前端需 `package.json`，Java 需 Maven/Gradle 构建文件，.NET 需 `.sln`、`.slnx` 或 `.csproj`；不要选择 dist、JAR 或 DLL 成品目录。
4. 选择生产目录，确认前端发布方式、端口和触发规则。Java 后端按需填写生产数据库设置；.NET 使用独立生产配置。
5. 生成预览，检查项目、Job、路径和端口，然后确认“创建 / 继续部署”。软件按后端 → 前端顺序触发并监控首次构建。
6. 所有目标 Job SUCCESS 后显示带透明角色图片的成功弹窗。有 Redis 配置时会再提示确认 Redis 可用，软件不会安装或启动 Redis。
7. 后续提交由 Gerrit 触发规则处理。服务看板可查看受管 WinSW 后端和 IIS 前端，并进行启停或移除注册。

## 配置、恢复与边界

- Agent 文件保存在用户选择磁盘的 `JenkinsDeployTool/agent`；生产文件保存在用户选择的生产目录。
- 自动重试仅针对模板明确识别的有限临时故障。已进入生产阶段、自定义流水线或日志不完整时不会整体自动重跑。
- 已成功、归属不明或被人工修改的 Job 受保护。升级客户端不会自动替换已有成功 Job 的 Pipeline。
- .NET 首次部署会准备 upload/uploads/logs；服务 Running、TCP 连接及可选 HTTP 健康检查连续通过后才算就绪。验收不保证服务未来永不退出。
- 看板停止服务会禁止自启；再次手动启动才恢复。删除操作移除受管注册，保留生产文件。
- 失败时先查看 Jenkins 控制台与 `backend/service/logs`，再使用“导出诊断”；诊断、生产配置与备份可能含部署环境信息，不要直接公开。
- Nginx 静态部署需要管理员自行配置生产反向代理；Vite 开发代理不会自动成为生产代理。

## 第三方组件

WinSW、IIS 离线组件的来源与许可说明见各自 `packaging/` 子目录。请保留第三方许可文件。项目未额外声明统一开源许可证，不能据仓库可访问性推断其他授权。
