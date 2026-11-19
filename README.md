# Rancher View

在 IntelliJ IDEA 中直接查看公司 Rancher 命名空间下所有 Deployment 的运行状态与 Pod 日志，无需来回切换浏览器。

## 功能

- **命名空间级监控**：按项目配置一个 Rancher 命名空间（如 `test`），列表展示该空间下全部 Deployment 的就绪 / 更新 / 镜像 / Pod 数 / 年龄
- **上下分栏联动**：上方服务列表，选中后下方显示该服务的概要信息和 Pod 表；分栏比例可拖动并持久化（默认 7:3）
- **名称筛选**：工具栏搜索框输入即过滤，刷新后保留筛选词与选中行
- **Pod 日志**：双击 Pod 在编辑器打开日志 Tab，自动折行、按级别配色（ERROR 红 / WARN 琥珀 / 堆栈红 / 时间戳灰），每 5 秒刷新；向上翻看时不会强制跳到底部
- **状态栏聚合**：如 `● test 66/68`，异常时 `✖ test 64/68 · 2异常`，点击打开工具窗
- **异常通知**：服务变为「异常」且连续两次刷新确认后弹出错误通知；发布完成不通知（正在删除的旧 Pod 不参与失败判定，避免滚动发布误报）
- **测试连接**：配置页一键校验地址 / 集群 / 命名空间 / Token 是否可用
- **关闭工具窗后暂停刷新**（可选）：勾选后关闭窗口即停止请求，重新打开 1 秒内自动恢复

## 环境要求

- IntelliJ IDEA 2024.2+（`since-build` 242）
- 可访问 Rancher 服务地址（默认 `https://ds-oms.gree.com:8888`，可在配置页修改）

## 安装

1. 下载 `build/distributions/rancher-view-0.1.0.zip`
2. IDEA → Settings → Plugins → 齿轮 → **Install Plugin from Disk**，选择该 zip
3. 重启 IDEA

## 配置

Settings → Tools → **Rancher 服务**：

| 配置项 | 说明 |
| --- | --- |
| Rancher 地址 | 可粘贴 dashboard 完整地址，失焦后自动解析集群与命名空间 |
| 集群 ID | 如 `c-m-zw299nxw` |
| 命名空间 | 如 `test`，该空间下所有 Deployment 都会出现在列表中 |
| 刷新间隔（秒） | 3–300，默认 10 |
| 发布异常时弹出通知 | 按服务触发；异常需连续两次刷新确认，发布完成不通知 |
| 关闭工具窗后暂停刷新 | 默认关闭 |
| API Token | Rancher 右上角头像 → Account & API Keys 创建，建议只授权当前集群 |

Token 保存在 IDE 凭据存储（PasswordSafe）中，按 Rancher 地址区分，**不会写入项目配置文件**。

## 使用

1. 右侧边栏打开 **Rancher** 工具窗
2. 上方列表查看所有服务状态，搜索框按名称过滤
3. 单击行在下方查看 Pod 详情，双击 Pod 打开日志 Tab
4. 「刷新」按钮手动拉取；状态栏常驻显示命名空间聚合状态

## 构建

```bash
# JDK 21
./gradlew buildPlugin
# 产物：build/distributions/rancher-view-0.1.0.zip
```

开发调试（启动沙箱 IDE）：

```bash
./gradlew runIde
```

## 项目结构

```
src/main/kotlin/com/nathan/rancherview/
├── api/          # Rancher Steve API 客户端（Deployment/Pod 列表、Pod 日志）
├── config/       # 项目级配置与设置页
├── model/        # ServiceInfo / PodInfo / NamespaceSnapshot
├── service/      # 轮询服务、通知
├── statusbar/    # 状态栏组件
├── toolwindow/   # 工具窗（列表 + 详情）、日志 Tab 服务
└── util/         # URL 解析、Token 存取
```

## 接口说明

- 服务列表：`GET /k8s/clusters/{clusterId}/v1/apps.deployments/{namespace}`（Steve `data` 数组）
- Pod 列表：`GET /k8s/clusters/{clusterId}/v1/pods/{namespace}`，按 Deployment 的 `matchLabels` 归组
- Pod 日志：`GET /k8s/clusters/{clusterId}/api/v1/namespaces/{namespace}/pods/{pod}/log?tailLines=300`
- 认证：`Authorization: Bearer <token>`
