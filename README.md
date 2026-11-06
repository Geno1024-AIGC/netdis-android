# 网络拓扑（NetDis）

一个只做一件事的 Android 局域网工具：扫描当前网络、登录多级路由器、把结果画成一张可缩放的家用网络拓扑图。

- **设备发现**：ARP 表、TCP 端口探测、mDNS、SSDP/UPnP、DNS 反查、NetBIOS、路由器 DHCP 租约
- **多级路由**：根据 WAN 地址与上级网关把二级、三级路由自动挂到正确的位置
- **路由器登录**：OpenWrt / LuCI（ubus JSON-RPC）、MikroTik RouterOS 7（REST），以及中兴、TP-Link、水星、华硕、小米、华为、腾达、爱快、锐捷、H3C、Netgear、D-Link 等网页管理页；应用内点按卡片即可登录并刷新
- **设备备注**：给未知设备起名、写备注，按 MAC 跟随设备，重新扫描后仍然生效
- **抓包排障**：按需记录应用与路由器之间的 HTTP 请求/响应，可查看、导出，便于适配新固件
- **离线缓存**：保留上次扫描结果与设备清单，重新打开应用即可查看
- **滚动更新**：GitHub Actions 构建签名的 canary APK，应用内检查、下载、校验并安装

最低支持 Android 8.0（API 26）。

## 界面

| 页面 | 内容 |
|---|---|
| 拓扑 | 扫描进度、在线设备与已登录路由统计、可平移缩放的拓扑画布 |
| 设备 | 按名称 / IP / MAC / 厂商搜索，筛选全部、在线、离线、路由器 |
| 路由器 | 添加、编辑、启停、删除管理账号；点按卡片立即登录并刷新，显示登录状态、型号、客户端数与 WAN 地址 |
| 设置 | 主题、手动网段、深度扫描、离线设备、Root ARP、探测超时、抓包、更新渠道 |

点按拓扑中的任意节点会打开详情页：设备显示身份证据（MAC、厂商、端口、服务、发现方式），并可以添加或清除本机备注；路由器显示固件、运行时间、WAN 上联与客户端列表，并可直接在该页登录刷新。未登录的网关会直接提供「添加登录凭据」入口。

设备备注以 MAC 为主要键（MAC 不可用时退回 IP），只保存在本机。备注会在加载快照、完成扫描和单独登录路由器之后重新应用，因此改备注不需要重新扫描整个网段。

## 路由器适配与扩展

`router/RouterAdapters.kt` 是唯一的适配器注册表。新增品牌只需要：

1. 实现 `RouterAdapter`（`scan(config, timeoutMs)` 返回统一的 `RouterScanOutcome`）；
2. 把它加入 `RouterAdapters.all`。

路由器编辑页的芯片、自动识别探测顺序和应用内登录都会自动包含新适配器。`AUTO` 会依次尝试所有适配器，登录页指纹（`RouterFingerprintProbe.detectBrand`）只用于把更可能的品牌排到前面，猜错只会多几次请求，不会导致登录失败。

网页管理类品牌共用一个引擎（`router/WebUiAdapter.kt`）和解析层（`router/WebUiParsing.kt`）：适配器只描述登录页、登录请求与客户端/状态页，引擎负责 Cookie、令牌、表单/JSON 提交和失败判断。解析层同时接受 HTML 表格、直接返回的 JSON，以及脚本里内嵌的 JSON（例如 `var devicelist = {...}`），不依赖固定列号，因此固件换皮后通常仍能读到客户端。品牌清单在 `router/WebUiAdapters.kt`。

中兴适配器（`router/ZteAdapter.kt`）单独保留，因为它需要 `/asp/GetRandCount.asp` 随机数 + base64 `/login.gch` 的特殊流程；其他网页品牌由 `WebUiAdapters.kt` 中的小型描述接入。

## 抓包

「设置 → 抓包」可以打开应用与路由器之间的 HTTP 记录。它只记录本应用访问路由器管理页的请求与响应，不抓取其他应用或整个网卡的流量，也不需要 root。

- 默认关闭；打开后只在内存中保留最近 200 条，重启应用即清空。
- 写入前会屏蔽账号、密码、Cookie、`Authorization` 等请求头，以及 URL / 表单中的 `password`、`token`、`session` 字段。
- 「查看」显示最近 20 条预览；「导出」把完整文本写入应用缓存并打开系统分享面板，由用户决定保存或发送到哪里。
- 新固件登录失败时，先打开抓包，再点一次路由器卡片，就能看到实际请求被哪一步拒绝，然后据此调整适配器。

## 扫描原理

1. 读取当前 Wi-Fi / 以太网接口、网关与 DNS，也支持手动指定 `192.168.x.0/24` 网段。
2. 先向网段内每个地址发送唤醒包，再读取本机 ARP 表；端口探测结束后会再读一次，补上探测期间才解析出来的 MAC。
3. 并发探测地址的常用端口，同时发送 mDNS / SSDP / NetBIOS 查询。
4. 登录已配置的路由器（按适配器注册表自动识别或指定品牌），读取 DHCP 租约、ARP、无线关联、LAN 网段与 WAN 信息；只有路由器知道的客户端也会补全 OUI 厂商与设备类型。点按路由器卡片可以只登录这一台并立即更新拓扑，不必重新扫描。
5. 按 IP 合并所有证据，再由 `TopologyBuilder` 生成多级拓扑。

Android 10 起系统禁止普通应用读取 `/proc/net/arp`，Android 12 起连 netlink 邻居表也一并封锁，**没有任何运行时权限可以解除**。因此在这类设备上：

- 推荐在「路由器」页登录网关。DHCP 租约 / ARP 表能提供本网段及下级路由器的全部 MAC 与厂商信息。
- 已 root 且授予 su 权限时，可在「设置 → 扫描」打开「Root 读取 ARP 表」，应用会改用 `su -c cat /proc/net/arp` 读取本网段。
- 两条路都没有时，扫描仍能靠 TCP / ICMP / mDNS / SSDP / NetBIOS 发现设备，只是 MAC 与厂商可能为空，结果页会给出对应提示。

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

首次安装需要从发布页手动下载 APK。之后可以在「设置 → 更新」中检查并安装新版本；如果系统尚未允许本应用安装未知来源应用，应用会先跳转到授权页。应用包名是 `com.geno1024.ai.netdis`；包名变更后（例如从旧包名升级）需要手动安装一次，无法由应用内更新覆盖。

版本号沿用同组织 Android 项目的三段式构建标记：

- `versionName = 0.1.<env>.<pack>.<sha8>`
  - `env`：CI 为 GitHub run number，本地为提交数量，决定发布顺序
  - `pack`：本地打包次数（`app/count.pack`，纳入 Git 管理）。每次本地打包先自增再写进版本号，所以 APK 里的数字就是这次构建的序号，文件的值等于已完成的本地打包次数；CI 不修改它，发布版沿用最后一次提交的计数
  - `sha8`：提交短哈希
- `versionCode = env`，应用内更新器用它判断是否有新构建

CI 直接从构建出的 APK 里读取版本号，而不是重新计算，确保发布标题、资产名与 APK 实际版本一致。CI 不参与本地打包计数，因此发布版里的 `pack` 就是构建该提交时本地已完成的打包次数。本地打包后应把 `app/count.pack` 的变化一并提交，避免计数与历史脱节。

## 隐私与安全

- 路由器密码默认使用 Android Keystore 中的 AES-GCM 密钥加密后保存在本机 SharedPreferences。
- 如果本机 Keystore 无法生成密钥（root、定制 ROM 或 `ATTESTATION_KEYS_NOT_PROVISIONED` 等故障），应用会退回使用应用私有存储中的软件 AES 密钥；凭据仍可用，但不再有硬件保护。「设置 → 关于」会标明当前用的是哪一种。
- 扫描只发生在当前局域网，不上传设备清单或凭据。
- 连接路由器管理页时允许自签名证书（仅限局域网设备）；更新下载走正常 TLS 并校验 SHA-256。
- 抓包默认关闭，只在内存中保留最近 200 条，账号、Cookie 与会话令牌在写入前打码；导出文件由用户主动分享，不会自动上传。
- 应用不申请位置权限；Wi-Fi 名称等信息来自系统网络能力接口。

## 已知限制

- MikroTik 需要 RouterOS 7 并启用 `www` / `www-ssl` REST 服务。
- OpenWrt 需要可访问的 `ubus` 端点与 LuCI 账号；部分裁剪固件可能未安装 `luci-rpc`。
- 网页管理类品牌（中兴、TP-Link、水星、华硕、小米、华为、腾达、爱快、锐捷、H3C、Netgear、D-Link）的固件差异很大；适配器已覆盖常见登录流程、HTML 表格与 JSON/内嵌 JSON 客户端列表，但个别固件可能只能登录、读不到客户端，可用「设置 → 抓包」定位后调整。
- 部分新固件要求密码预哈希、图形验证码或多步令牌交换；适配器会尝试常见方案，失败原因会显示在路由器卡片上。抓包可以看到具体是哪一步被拒绝。
- 未单独适配的品牌仍会通过登录页指纹显示品牌并作为网关出现在拓扑中；新增品牌按「路由器适配与扩展」接入。
- 蜂窝网络下运营商通常隔离局域网，扫描结果可能为空。
- 拓扑布局针对家庭网络规模优化，超大网段会限制扫描地址数量。
- 无锁屏密码本身不影响凭据加密；但如果 Keystore 报 `-74 ATTESTATION_KEYS_NOT_PROVISIONED`（常见于 root 或定制 ROM），硬件密钥无法生成，此时密码由软件密钥加密。

## 代码结构

```
app/src/main/kotlin/com/geno1024/ai/netdis/
├── model/      共享数据模型
├── net/        地址、ARP、DNS、mDNS、SSDP、NetBIOS、端口探测与扫描编排
├── router/     适配器注册表、OpenWrt / MikroTik / ZTE / 网页品牌适配器、HTTP 会话与抓包
├── topo/       拓扑构建与布局
├── storage/    Keystore 加密、设置、设备备注、路由器与快照持久化
├── update/     canary 清单、镜像、下载校验与安装 Intent
└── ui/         Compose 单 Activity 界面
```
