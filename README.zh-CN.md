# Rancher View

[English](README.md) | [简体中文](README.zh-CN.md)

在 IntelliJ IDEA 中直接查看 Rancher 各命名空间下所有 Deployment 的运行状态与 Pod 日志，无需来回切换浏览器。

## 功能

- **命名空间切换**：工具窗左上角下拉框选择命名空间（默认 `default`），选择会自动保存；列表展示该空间下全部 Deployment 的就绪 / 更新 / 镜像 / Pod 数 / 年龄
- **上下分栏联动**：上方服务列表，选中后下方显示该服务的概要信息和 Pod 表；分栏比例可拖动并持久化（默认 7:3）
- **名称筛选**：工具栏搜索框输入即过滤，刷新后保留筛选词与选中行
- **Pod 日志**：双击 Pod 在编辑器打开日志 Tab，自动折行、按级别配色（ERROR 红 / WARN 琥珀 / 堆栈红 / 时间戳灰），每 5 秒刷新；向上翻看时不会强制跳到底部；不同命名空间的日志互不影响
- **状态栏聚合**：如 `● default 66/68`，异常时 `✖ default 64/68 · 2异常`，点击打开工具窗
- **异常通知**：服务变为「异常」且连续两次刷新确认后弹出错误通知；发布完成不通知（正在删除的旧 Pod 不参与失败判定，避免滚动发布误报）
- **测试连接**：配置页一键校验地址 / 集群 / 命名空间 / Token 是否可用
- **关闭工具窗后暂停刷新**（可选）：勾选后关闭窗口即停止请求，重新打开 1 秒内自动恢复
- **中英双语界面**：支持英文与简体中文（默认英文），在设置中切换后立即生效，无需重启 IDE

## 环境要求

- IntelliJ IDEA 2024.2+（`since-build` 242）
- 可访问你的 Rancher 服务地址

## 安装

从 JetBrains Marketplace 安装：

1. IDEA → Settings → Plugins → **Marketplace**，搜索 **Rancher View**
2. 安装后重启 IDE

从本地包安装：

1. 下载 `rancher-view-2.1.1.zip`
2. IDEA → Settings → Plugins → 齿轮 → **Install Plugin from Disk**，选择该 zip
3. 重启 IDEA

## 配置

Settings → Tools → **Rancher 服务**（切换为英文界面时显示为 “Rancher Service”）：

| 配置项 | 说明 |
| --- | --- |
| 语言 | English / 中文（简体）；全局保存，所有项目共用，默认英文 |
| Rancher 地址 | 必填，无默认值；可填服务器地址，也可粘贴 dashboard 完整地址（失焦后自动解析集群与命名空间）。结尾的 `/v3` 会被去掉，带 `/dashboard` 的地址会被拒绝 |
| 集群 ID | 如 `c-m-zw299nxw` |
| 默认命名空间 | 留空时使用 `default`；工具窗左上角可随时下拉切换 |
| 刷新间隔（秒） | 3–300，默认 10 |
| 发布异常时弹出通知 | 按服务分别判断；异常需连续两次刷新确认，发布完成不通知 |
| 关闭工具窗后暂停刷新 | 默认关闭 |
| API Token | Rancher 右上角头像 → Account & API Keys 创建，建议只授权当前集群 |

Token 保存在 IDE 凭据存储（PasswordSafe）中，按 Rancher 地址区分，**不会写入项目配置文件**。

## 使用

1. 右侧边栏打开 **Rancher View** 工具窗
2. 左上角下拉框选择命名空间（默认 `default`），列表展示该空间下所有服务状态，搜索框按名称过滤
3. 单击行在下方查看 Pod 详情，双击 Pod 打开日志 Tab
4. 「刷新」按钮手动拉取（同时重新加载命名空间列表）；状态栏常驻显示当前命名空间聚合状态

## 构建

```bash
# JDK 21
./gradlew buildPlugin
# 产物：build/distributions/rancher-view-2.1.1.zip
```

开发调试（启动沙箱 IDE）：

```bash
./gradlew runIde
```

## 发布

```bash
# 签名（可选，推荐）：需要 CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD
./gradlew signPlugin

# 发布到 JetBrains Marketplace：需要 PUBLISH_TOKEN
./gradlew publishPlugin
```

## 开源协议

MIT。本项目为独立第三方插件，与 SUSE / Rancher Labs 无隶属或背书关系。

## 项目结构

```
src/main/kotlin/com/nathan/rancherview/
├── api/          # Rancher Steve API 客户端（Deployment/Pod 列表、Pod 日志）
├── config/       # 项目级配置与设置页
├── model/        # ServiceInfo / PodInfo / NamespaceSnapshot
├── service/      # 轮询服务、通知
├── statusbar/    # 状态栏组件
├── toolwindow/   # 工具窗（列表 + 详情）、日志 Tab 服务
└── util/         # URL 解析、Token 存取、多语言资源
```

## 接口说明

- 命名空间列表：`GET /k8s/clusters/{clusterId}/v1/namespaces`（Steve `data` 数组）
- 服务列表：`GET /k8s/clusters/{clusterId}/v1/apps.deployments/{namespace}`（Steve `data` 数组）
- Pod 列表：`GET /k8s/clusters/{clusterId}/v1/pods/{namespace}`，按 Deployment 的 `matchLabels` 归组
- Pod 日志：`GET /k8s/clusters/{clusterId}/api/v1/namespaces/{namespace}/pods/{pod}/log?tailLines=300`
- 认证：`Authorization: Bearer <token>`
