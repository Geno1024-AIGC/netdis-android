# 网络拓扑（NetDis）

一个只做一件事的 Android 局域网工具：扫描当前网络、登录多级路由器、把结果画成一张可缩放的家用网络拓扑图。

- **设备发现**：ARP 表、TCP 端口探测、mDNS、SSDP/UPnP、DNS 反查、NetBIOS、路由器 DHCP 租约
- **多级路由**：根据 WAN 地址与上级网关把二级、三级路由自动挂到正确的位置
- **路由器登录**：OpenWrt / LuCI（ubus JSON-RPC）与 MikroTik RouterOS 7（REST）
- **离线缓存**：保留上次扫描结果与设备清单，重新打开应用即可查看
- **滚动更新**：GitHub Actions 构建签名的 canary APK，应用内检查、下载、校验并安装

最低支持 Android 8.0（API 26）。

## 界面

| 页面 | 内容 |
|---|---|
| 拓扑 | 扫描进度、在线设备与已登录路由统计、可平移缩放的拓扑画布 |
| 设备 | 按名称 / IP / MAC / 厂商搜索，筛选全部、在线、离线、路由器 |
| 路由器 | 添加、编辑、启停、删除管理账号；显示登录状态、型号、客户端数与 WAN 地址 |
| 设置 | 主题、手动网段、深度扫描、离线设备、探测超时、更新渠道 |

点按拓扑中的任意节点会打开详情页：设备显示身份证据（MAC、厂商、端口、服务、发现方式），路由器显示固件、运行时间、WAN 上联与客户端列表。未登录的网关会直接提供「添加登录凭据」入口。

## 扫描原理

1. 读取当前 Wi-Fi / 以太网接口、网关与 DNS，也支持手动指定 `192.168.x.0/24` 网段。
2. 并发探测地址的常用端口，同时发送 mDNS / SSDP / NetBIOS 查询。
3. 读取本机 `/proc/net/arp`，并用 OUI 表补全厂商。
4. 登录已配置的路由器，读取 DHCP 租约、ARP、无线关联、LAN 网段与 WAN 信息。
5. 按 IP 合并所有证据，再由 `TopologyBuilder` 生成多级拓扑。

部分 Android 版本会限制读取 `/proc/net/arp`，此时 MAC 和厂商信息可能缺失，应用会在扫描结果里给出提示。

## 构建

环境要求：

- JDK 21+
- Android SDK，`compileSdk = 37`
- 仓库自带的 Gradle Wrapper（无需系统安装 Gradle）

```bash
# 指向本机 SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleDebug        # 调试包
./gradlew testDebugUnitTest    # 单元测试
./gradlew lintRelease          # Lint
./gradlew assembleRelease      # 签名发布包（需要下面的 keystore）
```

### 发布签名

按组织规范 `RULES/KEYSTORE.md`，签名信息只从环境变量或 `local.properties` 读取，绝不硬编码：

```properties
# local.properties（git-ignored）
keystore.storeFile=/absolute/path/to/keystore.jks
keystore.storePassword=...
keystore.keyPassword=...
keystore.keyAlias=geno
```

CI 使用 org 级 secrets `KEYSTORE_BASE64`、`KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_PASSWORD`、`KEYSTORE_KEY_ALIAS`。工作流会把 keystore 临时物化到 runner，构建后校验 APK 确实已签名，再发布。

## 更新流程

每次推送到 `master`，`.github/workflows/build.yml` 会：

1. 运行单元测试与 `lintRelease`；
2. 用共享 keystore 构建签名 release APK；
3. 读取版本号并验证签名；
4. 覆盖发布滚动预发布版本 [`canary`](https://github.com/Geno1024-AIGC/netdis-android/releases/tag/canary)，同时上传 APK 与 `canary.properties`。

`canary.properties` 包含构建号、版本名、文件大小与 SHA-256。应用内的更新器直接读取该清单，下载时逐字节校验大小和摘要；最终安装仍由 Android 校验应用签名，因此他人无法用不同密钥的 APK 替换已安装版本。

首次安装需要从发布页手动下载 APK。之后可以在「设置 → 更新」中检查并安装新版本；如果系统尚未允许本应用安装未知来源应用，应用会先跳转到授权页。

版本号规则：`versionName = 0.1.<GitHub run number>.<commit sha 前 8 位>`，`versionCode = GitHub run number`。

## 隐私与安全

- 路由器密码使用 Android Keystore 中的 AES-GCM 密钥加密后保存在本机 SharedPreferences。
- 扫描只发生在当前局域网，不上传设备清单或凭据。
- 连接路由器管理页时允许自签名证书（仅限局域网设备）；更新下载走正常 TLS 并校验 SHA-256。
- 应用不申请位置权限；Wi-Fi 名称等信息来自系统网络能力接口。

## 已知限制

- MikroTik 需要 RouterOS 7 并启用 `www` / `www-ssl` REST 服务。
- OpenWrt 需要可访问的 `ubus` 端点与 LuCI 账号；部分裁剪固件可能未安装 `luci-rpc`。
- 其他品牌路由器目前只做指纹识别，不能读取客户端列表；但仍会作为网关显示在拓扑中。
- 蜂窝网络下运营商通常隔离局域网，扫描结果可能为空。
- 拓扑布局针对家庭网络规模优化，超大网段会限制扫描地址数量。

## 代码结构

```
app/src/main/kotlin/com/geno1024/netdis/
├── model/      共享数据模型
├── net/        地址、ARP、DNS、mDNS、SSDP、NetBIOS、端口探测与扫描编排
├── router/     OpenWrt / MikroTik 适配器与自动识别
├── topo/       拓扑构建与布局
├── storage/    Keystore 加密、设置、路由器与快照持久化
├── update/     canary 清单、镜像、下载校验与安装 Intent
└── ui/         Compose 单 Activity 界面
```
