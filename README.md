# UBAA (智慧北航 Remake)

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-blue.svg?style=flat&logo=kotlin)
![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.10.3-blueviolet.svg?style=flat&logo=jetpack-compose)
![Ktor](https://img.shields.io/badge/Ktor-3.4.1-orange.svg?style=flat&logo=ktor)
![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20Web-lightgrey.svg?style=flat)

**UBAA** 是一款面向北京航空航天大学学生的跨平台客户端，基于 **Kotlin Multiplatform**、**Compose Multiplatform** 和 **Ktor** 构建，覆盖 Android、iOS、Desktop 与 Web。

它不仅是客户端，也是一套统一的校园服务聚合方案：服务端负责适配和清洗校内系统数据，客户端在多端提供一致、现代的使用体验。

## 入口

- 下载发布版：[GitHub Releases](https://github.com/BUAASubnet/UBAA/releases)
- 在线使用：[网页版](https://app.buaa.team)
- 开发文档：[项目文档](docs/index.md)

### Arch Linux (AUR)

```bash
yay -S ubaa
```

## 核心能力

- 多端统一：Android、iOS、Desktop、Web 共用核心能力与大部分业务代码。
- 校园服务聚合：统一认证、课表、考试、成绩、空闲教室、博雅、SPOC、希冀、图书馆座位、签到、研讨室、阳光打卡、评教等能力集中接入。
- 全栈同仓：`shared` 统一前后端契约，`server` 负责网关与会话管理，`composeApp` 负责跨平台 UI。
- 现代体验：基于 Material Design 3，支持系统主题适配与持续更新。

## 开发文档

详细文档统一维护在仓库 `docs/` 中，并由 VitePress 构建为静态站点。推送 `dev` 分支后，GitHub Actions 会构建文档并通过 SSH rsync 发布到服务器静态目录。

- 总览入口：[项目文档](docs/index.md)
- 功能说明：[功能总览](docs/features/index.md) / [登录与连接模式](docs/features/auth-and-connection.md) / [希冀作业](docs/features/judge.md)
- 技术文档：[架构总览](docs/tech/architecture.md) / [共享 API 与契约](docs/tech/shared-api.md) / [服务端路由](docs/tech/server-routes.md)
- 发布与排障：[发布与部署](docs/tech/release-deployment.md) / [配置说明](docs/tech/configuration.md) / [排障指南](docs/tech/troubleshooting.md)
- 项目公告与版本：[公告维护](docs/announcements/index.md) / [更新日志](docs/changelog/index.md)

## 仓库概览

```text
UBAA/
├── composeApp/   # 跨平台客户端 UI
├── shared/       # 前后端共享契约与通用逻辑
├── server/       # Ktor 后端网关
├── androidApp/   # Android 壳工程
├── iosApp/       # iOS 壳工程
├── buildSrc/     # 自定义 Gradle 任务
├── docs/         # VitePress 文档
└── .github/      # CI、发布和文档部署 workflow
```
