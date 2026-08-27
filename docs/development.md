# PikaDesk 开发环境

## 固定工具版本

- JDK：Eclipse Temurin 21.0.12.1+1（Windows x64 开发基线）。
- Maven：3.9.16，由仓库中的 Maven Wrapper 自动下载。
- JUnit：5.14.4。
- Maven Surefire：3.5.4。

Maven Wrapper 的下载地址和 SHA-256 固定在 `.mvn/wrapper/maven-wrapper.properties`。项目不需要全局安装 Maven，也不把本地 JDK、Maven 缓存或构建产物提交进 Git。

## Windows PowerShell

先安装或解压 JDK 21，然后只为当前终端设置 `JAVA_HOME`：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd --version
.\mvnw.cmd --batch-mode test
.\mvnw.cmd --batch-mode verify
```

本次开发使用的 Temurin ZIP 来自 Adoptium 官方 API，文件 SHA-256 为：

```text
f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e
```

Apache Maven 3.9.16 ZIP 先通过官方 SHA-512 验证，再把计算出的 SHA-256 写入 Wrapper：

```text
SHA-512 ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3
SHA-256 5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
```

## 测试纪律

1. 新行为先写失败测试并确认失败原因正确。
2. 写最小实现让测试通过。
3. 重构后重新运行受影响测试。
4. 提交前运行完整 `verify`，不得跳过或禁用测试。

## Windows 本地自动化 E2E

端到端鼠标测试只允许对仓库自带的离线测试棋盘执行。启动命令、受限探针命令、已通过组合和未完成门槛见 [`automation-e2e.md`](automation-e2e.md)。该测试会产生一次真实点击对，运行前必须让测试棋盘完整可见，并恢复到标准开局。

## Windows 本地开发镜像

直接执行 `javafx:jlink` 会因 `slf4j-api`、`jnativehook` 等自动模块而失败。仓库采用两阶段打包：先用 JDK 模块生成精简运行时，再由 `jpackage` 把应用和自动模块放到正常 module-path。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\scripts\package-windows.ps1
.\target\windows-app-image\PikaDesk\PikaDesk.exe
```

脚本强制执行 `clean verify`，不提供跳过测试参数；随后校验启动器、JRE、模型和外部素材，并生成 `SHA256SUMS.txt`。产物是免安装的本地开发 `app-image`，不是公开安装包。模型来源发布门解除前，不得公开再分发。

## 当前已知构建警告

- `jnativehook-2.1.0.jar` 是基于文件名的自动模块，Maven 编译器提示不应把当前模块发布到公共 Maven 制品库。PikaDesk 的交付目标是桌面运行时镜像/安装包，不把该 JAR 形式发布到 Maven Central。
- 现有 `Engine.java` 使用了过时 API，`LocalBookController.java` 有未检查操作；它们在特征测试建立后分开处理，不与本构建切片混改。
