# 架构与设计决策

## 模块依赖

```text
JavaFX UI
  → ApplicationService（用例编排）
      → JenkinsClient（Controller API）
      → GerritClient（由进程环境变量配置的项目只读查询）
      → AgentProvisioningService（JRE / agent.jar / WinSW）
          → ExistingAgentDiscovery（只读 Windows 服务 / WinSW XML 身份发现）
      → ProjectDetectionService（本地只读扫描）
      → PipelineTemplateService（Java/前端模板编排）
          → DotNetPipelineTemplate（隔离的 .NET 10 模板）
      → RecentProjectService（上次源码目录持久化与有效性校验）
      → DeploymentHints / BackendServiceNaming（部署提示与稳定服务 ID）
      → PortAvailabilityService（本机 TCP 端口绑定探测）
      → LocalRuntimeDashboardService（受控 WinSW 服务 / IIS 站点查询与启停）
      → JobProvisioningService（Workflow Job XML、幂等写入、首次部署编排与失败分类）
      → EnvConfigService（本机 config.env）
      → InternalTlsSupport（公共 CA + 公司内部根 CA）
```

依赖方向从 UI 指向用例与基础设施，服务之间不相互读取内部状态。

## 技术决策

### JavaFX 替代 WPF

- **选择**：Java 21 + JavaFX 21。
- **理由**：当前开发机已有 JDK/Maven，但没有 .NET SDK；JavaFX 可在本机真实编译验证，并通过 `jpackage` 生成 Windows 安装包。
- **取舍**：Windows 服务与 ACL 通过受控系统命令集成，不如 WPF 原生 API 直接。
- **撤销条件**：后续团队必须统一 .NET，或 JavaFX 打包无法满足目标 Windows Server 版本。

### Job 配置由应用生成

- **选择**：由版本化代码生成前端/后端 Workflow Job XML，写入前强制预览和二次确认。
- **理由**：能够将受控的 Gerrit 事件、分支匹配规则与可选 SCM 轮询写入 Jenkins XML，并按项目目录生成互斥路径过滤器；默认仍保持 `Ref Updated` 与 `refs/heads/dev`。
- **风险控制**：Gerrit Trigger 插件 XML 会随版本变化，因此首次使用必须在测试 Controller 验证；插件版本不兼容时禁止直接用于生产。

### Agent 安装盘由用户选择

- **选择**：界面列出全部可用磁盘，用户必须选定盘符；大体积运行文件写入 `<盘符>:\JenkinsDeployTool\agent`。
- **理由**：服务器磁盘规划不同，不能假定 C 盘空间充足。
- **边界**：仅小型共享配置保留在 `%ProgramData%`，以便管理员安装的 Windows 服务与普通用户共用设置。

### 内部 HTTPS 证书采用限定信任

- **选择**：保留 Java 默认公共 CA，并额外信任指纹固定的公司内部根证书；Agent 使用生成在所选盘的 PKCS12 信任库。
- **中断恢复**：Agent 身份在下载运行时文件前先持久化；启动时若身份缺失，则按受控命名前缀找回唯一在线节点。在线节点直接复用，已有可读取的 JAR 不重复下载，避免 Windows 文件锁导致误报离线。
- **理由**：修复私有 Java Runtime 的 PKIX 错误，同时不关闭证书链或主机名校验。
- **维护**：公司根证书轮换时，需要核对新证书指纹并随新版本更新。

### 预先安装的 Agent 使用本机证据复用

- **发现**：`ExistingAgentDiscovery` 只读 `Get-Service` 与服务注册表 ImagePath，读取 WinSW 同名 XML；不使用需要额外权限的 CIM、不扫全盘、不执行配置内命令。支持 `%BASE%`/环境变量、引号路径、`-url/-name` 和旧 `-jnlpUrl`。
- **身份**：按 XML 的实际 Controller URL（含 context path）与节点名读取 Jenkins 状态，允许任意节点名及本地安装目录，不依赖软件前缀/deployment 标签。原安装目录严格约束只适用于新安装，不放宽服务安装写入范围。
- **保护**：不读取 Secret 文件、不请求 JNLP Secret、不改原 exe/XML/JAR/证书，不安装/刷新/启停已有服务。离线保留 `AGENT_EXTERNAL=true` 身份并禁用重复安装；多个候选、归属矛盾或发现失败安全报错，不猜测。
- **部署资源**：外部 Agent 的辅助 WinSW、Java、IIS 放在同盘 `JenkinsDeployTool/deployment-support`，首次用户确认生产部署后才准备；生成 Pipeline 使用该路径，原 Agent Java 与信任库保持不变。软件原有安装继续使用原路径。
- **数据边界**：服务查询仅返回名称、wrapper 路径和状态，XML 解析禁止 DTD/外部实体，异常不携带原命令行；查询输出并发读取且限制 1 MiB，15 秒超时，避免 WinSW 服务较多时的管道堵塞和 4 KB 截断。

### 部署参数采用“源码提示 + 最少确认”

- **选择**：只读扫描 Spring Boot、Vite、Actuator 与 `web.config`，生成前端发布方式、后端端口、代理路径和健康检查提示；后端服务 ID 由项目名稳定生成。
- **理由**：实施人员不应记忆 WinSW 服务名，也不应填写可从源码或 Agent 配置获得的路径；同时避免把 IIS 写死，兼容静态目录/Nginx。
- **生产代理边界**：Vite `server.proxy` 只在开发服务器中生效。静态目录/Nginx 模式只给出代理提示；IIS 模式由受控模板自动创建站点级 `/api` 反向代理。
- **服务生命周期**：后端 Job 从 Agent 目录取得 WinSW 和 Java，首次安装生成的服务，后续执行 `refresh`、启动检查和失败回滚；服务 ID 冲突到其他可执行文件时立即停止。
- **生产配置边界**：桌面端从源码 Spring 配置只读识别 JDBC 模板，允许用户用 `IP:端口` 替换主机并保留数据库类型、库名和参数；JWT 密钥可选。最终配置写入用户选择目录下的 `backend/config/application-prod.yml`，采用临时文件原子替换并在 `backups/config` 留存旧版本；敏感值不进入 `DeploymentSettings`、Job XML和诊断日志。WinSW 固定启用 `prod` Profile 并加载该外部文件。
- **端口预检**：端口输入变化时使用短生命周期 `ServerSocket` 尝试绑定本机全部地址；绑定成功显示可用，失败显示占用。前后端同端口在 UI 层直接标红；该检查存在检测与部署之间的时间窗口，因此 Pipeline 保留最终冲突校验。

### 项目目录采用校验后恢复

- **选择**：仅在源码识别成功后保存规范化绝对路径；Agent 在线后的首次页面跳转会重新扫描该目录，通过后直接恢复第三步。
- **失效处理**：目录已删除、路径非法或构建标志文件消失时删除旧记录，保持第二步可操作并提示用户重选，不弹出启动错误框。
- **切换**：用户返回第二步仍可选择新目录；文件夹选择器默认打开到当前有效记录，新项目识别成功后覆盖旧记录。

### 首次部署采用有界自动编排

- **同名保护与续建**：`JobRecoveryService` 先只读生成 `JobPreparationPlan`，列出新建/原配置重试/备份更新/旧版认领/跳过成功部分，UI 单独确认。新版描述附本机 owner UUID、项目/仓库/Agent/生产目录 scope 和受管内容 SHA-256；未知来源、指纹被人工改动、运行/排队以及已成功脚本改动均拒绝。新建仍只用 `createItem`；已有未成功任务才可在原状态/config.xml 复核和本地私有备份后更新。旧版仅接受原助手描述与当前内置脚本/触发规则吻合的配置，认领只添加身份而不改脚本。XML 更新只替换脚本和受管触发字段，保留其他属性/触发器；绝不停止、取消或删除 Job。写入/触发前复核缩小竞态窗口，但 config.xml 无原子 CAS，不宣称服务端原子锁。
- **顺序**：Job 配置写入后先触发后端，成功后再触发前端；避免前端代理指向尚未运行的后端。
- **部分完成**：已成功部分复用原构建结果、次数记 0，不改配置、不重新部署；后端被跳过时也不重写本地生产配置。缺失 Job 补建，失败部分在用户明确确认后重跑；全部成功时拒绝再次首次部署。每个待运行 Job 在触发前再次核对状态/身份/脚本，避免等待另一个构建期间配置被改。
- **监控**：`JenkinsClient` 使用同一专用 Cookie 会话和 Crumb，读取 Queue API、Build API 与 `consoleText`，并限制排队与构建等待时间。
- **恢复**：内置模板在构建开始与部署阶段开始输出明确边界标记。只有完整日志证明尚未部署时，Agent/网络临时故障才最多总计三次；部署开始、证据缺失和自定义 Pipeline 不整体重跑。
- **共用产物助手**：`PipelineArtifactScripts` 把 `pipeline/artifacts.ps1` 以内嵌 Base64 注入生成的 Pipeline，不依赖外置脚本或新增插件。前端/.NET 压缩最多三次，失败间隔 5 秒；使用 .NET ZIP API 包含隐藏文件、清理残包，不探测持锁进程。
- **产物一致性**：ZIP 内部清单绑定仓库、类型和构建标识，逐文件检查长度/SHA-256、数量及必需入口；校验与解压使用同一打开的包，均在生产文件替换之前。JAR 要求唯一可部署产物、可执行 Main-Class 和所有内容可读，构建后记录指纹并在部署前复核。
- **生产文件保护**：前端保存备份完成与文件开始替换状态，复制及 HTTP 检查失败恢复旧目录；后端旧 WinSW XML 一并恢复，Java 健康检查放入部署保护范围。
- **后端回滚边界**：停服务与等待停止必须位于部署 `try/catch` 内；停服务成功后立即记录，后续异常只恢复本次部署主动停止的服务，避免误启动原本处于停止状态的服务。
- **扩展**：内置模板保持默认；自定义 Pipeline 是显式高级选项，并仍在 Jenkins Groovy Sandbox 中运行。
- **成功语义**：只有本次生成的所有 Job 都返回成功，桌面端才显示“部署成功”；局部成功或看板操作不使用该提示。

### .NET 10 采用隔离模板与框架依赖发布

- **选择**：识别层把 `.sln`、`.slnx`、`.csproj` 标记为 `dotnet` 后端；模板层立即分流到独立 `DotNetPipelineTemplate`，不在 Java Pipeline 中堆叠语言条件。
- **项目选择**：优先唯一的 Web SDK 项目；仓库仅有一个 `.csproj` 时使用该项目；其余歧义停止并列出候选，避免部署错误服务。
- **构建稳定性**：Agent 必须提供 .NET 10 SDK/Runtime。Restore 使用 `--disable-parallel --disable-build-servers` 并最多两次；Publish 使用已还原依赖且不整体重试，Deploy 失败立即进入回滚。
- **产物边界**：发布包移除 `appsettings.Production.json`、上传目录和日志，包含入口 DLL 标记及完整校验清单；必要的 DLL、deps.json、runtimeconfig.json 必须存在且非空。可选生产配置从 `backend/config` 注入，不进入 Jenkins 归档包；部署镜像同步排除生产 upload/uploads/logs 目录。
- **运行与回滚**：WinSW 执行 `dotnet <入口.dll>`，固定 Production 环境与本地监听端口。备份完成后才标记开始替换；备份或停服务失败不删除原应用。替换后失败恢复旧目录与 WinSW XML、卸载本次新建服务，并只恢复由本次部署停止的原服务。回滚失败同时保留原部署与回滚错误。

### 本机服务看板采用受控命名空间

- **后端范围**：只接受 `JdaBackend_*` 且服务可执行路径位于 `backend\service` 的 WinSW 服务，避免看板误操作其他 Windows 服务。
- **前端范围**：只显示根应用使用 `Jda_*` 应用池的 IIS 站点；启动和停止前再次校验应用池归属。
- **手动停机**：停止后端时把启动类型设为 `Disabled`，停止 IIS 时把 `serverAutoStart` 设为 `false`。后续内置 Pipeline 检测该标记，允许更新文件但保持停机并跳过运行态验证。
- **恢复运行**：用户在看板中启动时恢复后端 `Automatic` 或 IIS `serverAutoStart=true`，再启动对应运行项。所有变更都在本机执行并需要管理员权限。
- **只读兼容**：后端列表使用 `Get-Service` 加只读服务注册表，不依赖普通用户可能无权访问的 WMI/CIM。多行 PowerShell 使用 UTF-16LE `-EncodedCommand` 传输，并从成功输出中剥离 Windows PowerShell 首次模块加载产生的 CLIXML 进度前缀；空 JSON 数组是正常状态。
- **完整地址**：`dashboard/addresses.ps1` 只读现有 IIS 全部HTTP/HTTPS绑定和WinSW XML；后端支持.NET ASPNETCORE_URLS和Java显式port/address，结果写入ManagedRuntime不可变地址列表，UI只读可复制。loopback不扩为外网；wildcard IPv4使用本机网卡候选地址、IPv6通配保守显示回环；未知不猜默认值。显示“配置地址/未验证连通性”，不做HTTP探测或启停操作。XML禁DTD/外部实体、1MiB上限；不把完整命令行/配置输出到JSON。
- **查询容量**：看板查询单独使用1Mi字符上限，不再沿用动作输出4K截断；截断时拒绝部分列表。ProcessRunner并发有界读取stdout避免大量输出阻塞，原动作默认输出上限不变。
- **诊断边界**：退出GUI无停服务钩子；部署停服/失败回滚保留既有行为。SUCCESS后服务退出需目标应用/WinSW日志，当前无健康端点时仅瞬时Running校验，不能宣称业务长期健康。本轮地址功能不改Pipeline。
- **维护操作**：后端右键重命名只调用 `Set-Service -DisplayName`，内部服务 ID 与部署路径保持稳定。删除后端调用 SCM 删除服务注册；删除 IIS 站点时仅在对应 `Jda_*` 应用池未被其他站点引用时一并清理应用池。两类删除都不触碰生产目录，界面必须先确认。

### IIS 采用系统角色与可选离线组件

- **IIS 本体**：Windows Server 使用 `Install-WindowsFeature`，客户端 Windows 回退到 `Enable-WindowsOptionalFeature`；不分发 IIS 核心安装包。
- **反向代理**：URL Rewrite 2.1 x64 必须先于 ARR 3.0 x64 安装。官方 MSI 从软件包复制到持久 Agent `iis` 目录，流水线仅在系统未安装时静默调用。
- **幂等性**：应用池名自动生成；站点存在时更新物理路径和目标端口，不存在时创建；发现其他站点占用前端端口时停止而不是覆盖。
- **边界**：当前只自动管理无主机名 HTTP 绑定。HTTPS、SNI、域名和证书需要额外参数，未纳入基础三字段流程。
