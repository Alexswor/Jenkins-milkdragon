IIS 离线组件目录
================

IIS Web Server 本体是 Windows 自带角色，不需要额外安装包，软件会自动启用。

为了让软件在未预装反向代理组件的服务器上也能全自动配置 /api，本目录随发布包内置以下 Microsoft 官方 x64 安装包：

1. URL Rewrite 2.1 x64
   文件名：rewrite_amd64_zh-CN.msi
   https://www.iis.net/downloads/microsoft/url-rewrite

2. Application Request Routing 3.0 x64
   文件名：requestRouter_amd64.msi
   https://www.iis.net/downloads/microsoft/application-request-routing

安装顺序由软件固定为 URL Rewrite → ARR。打包脚本会校验固定 SHA-256 和 Microsoft 数字签名；请勿替换或修改安装包。
如果服务器已经安装对应组件，部署脚本会跳过重复安装。
