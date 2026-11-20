# Rancher View

[English](README.md) | [简体中文](README.zh-CN.md)

View the status of every Deployment and the pod logs in any Rancher namespace directly inside IntelliJ IDEA — no more switching to the browser.

## Features

- **Namespace switcher**: pick any namespace from the dropdown at the top-left of the tool window (defaults to `test`); the selection is remembered. The list shows ready / updated / image / pod count / age for every Deployment in that namespace.
- **Linked panes**: service list on top, service summary and pod table below; the splitter ratio is draggable and persisted (default 7:3).
- **Name filter**: filter as you type; the filter text and selected row survive refreshes.
- **Pod logs**: double-click a pod to open its log in an editor tab — auto-wrapped, level-colored (ERROR red / WARN amber / stack traces red / timestamps gray) and refreshed every 5 seconds. Scrolling up does not force a jump to the bottom, and log tabs are isolated per namespace.
- **Status bar summary**: e.g. `● test 66/68`, or `✖ test 64/68 · 2 failed` when something fails; click to open the tool window.
- **Failure notifications**: an error notification is shown only after a service stays failed for two consecutive refreshes. Successful rollouts are not announced, and terminating pods being replaced by a rollout are not counted as failures.
- **Test connection**: validate URL / cluster / namespace / token with one click in settings.
- **Pause when closed** (optional): stop polling when the tool window is closed; resumes automatically within 1 second after reopening.
- **Bilingual UI**: English and Simplified Chinese (English by default), switchable in settings and applied immediately without restarting the IDE.

## Requirements

- IntelliJ IDEA 2024.2+ (`since-build` 242)
- Network access to your Rancher endpoint (defaults to `https://ds-oms.gree.com:8888`, editable in settings)

## Install

1. Download `rancher-view-2.1.0.zip`
2. IDEA → Settings → Plugins → ⚙ → **Install Plugin from Disk…**, select the zip
3. Restart the IDE

## Configuration

Settings → Tools → **Rancher Service** (shown as “Rancher 服务” when Chinese is selected):

| Option | Description |
| --- | --- |
| Language | English / 中文（简体）; stored globally and shared by all projects, default English |
| Rancher URL | Paste the full dashboard URL; cluster and namespace are parsed when the field loses focus |
| Cluster ID | e.g. `c-m-zw299nxw` |
| Default namespace | e.g. `test`; can be switched anytime from the tool window dropdown. Empty means `test` |
| Refresh interval (s) | 3–300, default 10 |
| Notify when a deployment fails | Per service; requires two consecutive failed refreshes, successful rollouts are not announced |
| Pause polling when the tool window is closed | Off by default |
| API Token | Create it in Rancher from the avatar menu → Account & API Keys; grant access to the current cluster only |

The token is stored in the IDE credential store (PasswordSafe), keyed by Rancher URL, and is **never written to project files**.

## Usage

1. Open the **Rancher View** tool window from the right sidebar
2. Pick a namespace from the dropdown (defaults to `test`); the list shows every service in it, and the filter box searches by name
3. Click a row to see pod details below; double-click a pod to open its log tab
4. The **Refresh** button pulls data immediately (and reloads the namespace list); the status bar always shows the aggregate status of the current namespace

## Build

```bash
# JDK 21
./gradlew buildPlugin
# Output: build/distributions/rancher-view-2.1.0.zip
```

Run a sandbox IDE for development:

```bash
./gradlew runIde
```

## Project structure

```
src/main/kotlin/com/nathan/rancherview/
├── api/          # Rancher Steve API client (Deployment/Pod lists, pod logs)
├── config/       # Project-level settings and the settings page
├── model/        # ServiceInfo / PodInfo / NamespaceSnapshot
├── service/      # Polling service and notifications
├── statusbar/    # Status bar widget
├── toolwindow/   # Tool window (list + detail) and the log tab service
└── util/         # URL parsing, token storage, i18n bundle
```

## API notes

- Namespace list: `GET /k8s/clusters/{clusterId}/v1/namespaces` (Steve `data` array)
- Service list: `GET /k8s/clusters/{clusterId}/v1/apps.deployments/{namespace}` (Steve `data` array)
- Pod list: `GET /k8s/clusters/{clusterId}/v1/pods/{namespace}`, grouped by the Deployment `matchLabels`
- Pod logs: `GET /k8s/clusters/{clusterId}/api/v1/namespaces/{namespace}/pods/{pod}/log?tailLines=300`
- Authentication: `Authorization: Bearer <token>`
