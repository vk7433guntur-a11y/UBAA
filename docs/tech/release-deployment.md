# 发布与部署

仓库有三类发布：应用 release 构建、Web Wasm 上传、docs 静态站发布。docs 站点由 `dev` 分支 push 触发，构建后通过 SSH rsync 同步到服务器静态目录。

## 应用 Release

`.github/workflows/release.yml` 在 GitHub Release published 或手动触发时构建，并把产物直接上传到当前 Release：

- Android APK。
- Desktop Linux deb。
- Server fat jar。
- Web Wasm zip。
- Web JS zip。
- iOS framework。
- Windows exe。

## Web Wasm 上传

`.github/workflows/upload.yml` 构建 `:composeApp:wasmJsBrowserDistribution`，并把 `composeApp/build/dist/wasmJs/productionExecutable` 部署到 Cloudflare Pages。release 事件 checkout 可能处于 detached HEAD，因此 deploy 命令显式传入 `--branch=main`。

## Docs 发布

`.github/workflows/docs.yml` 在 `dev` 分支推送或手动触发时执行：

1. Checkout。
2. Setup Node 22。
3. `npm ci`。
4. `npm run docs:build`。
5. 校验 SSH Secrets。
6. 写入 SSH key 并执行 `ssh-keyscan`。
7. 在服务器创建目标目录。
8. `rsync -az --delete docs/.vitepress/dist/` 到 `DOCS_DEPLOY_PATH`。

服务器需要自行配置 Nginx、Caddy 或其他静态文件服务。本仓库只负责同步构建产物。

## 来源文件

- `.github/workflows/release.yml`
- `.github/workflows/upload.yml`
- `.github/workflows/docs.yml`
- `composeApp/src/webMain/resources/index.html`
- `composeApp/src/webMain/resources/sw.js`
- `package.json`
- `package-lock.json`
