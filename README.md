# 网络拓扑（NetDis）

一个只做一件事的 Android 局域网工具：扫描当前网络、登录多级路由器、把结果画成一张可缩放的家用网络拓扑图。

- **设备发现**：ARP 表、TCP 端口探测、mDNS、SSDP/UPnP、DNS 反查、NetBIOS、路由器 DHCP 租约
- **多级路由**：根据 WAN 地址与上级网关把二级、三级路由自动挂到正确的位置
- **路由器登录**：OpenWrt / LuCI（ubus JSON-RPC）、MikroTik RouterOS 7（REST），以及真实抓包适配的中兴 E1600、华为 HS8145C；另有 TP-Link、水星、华硕、小米、腾达、爱快、锐捷、H3C、Netgear、D-Link 等网页管理页；应用内点按卡片即可登录并刷新
- **凭据跟随设备**：路由器凭据优先按 LAN MAC 匹配，DHCP 换地址后仍能找到原账号
- **设备备注**：给未知设备起名、写备注，按 MAC 跟随设备，重新扫描后仍然生效
- **全端口扫描**：在设备详情里扫描 1–65535，应用内进度条与通知栏进度同步，可随时取消
- **主机侧抓包**：`tools/router-capture.py` 通过 Chromium CDP 记录登录后的请求与响应，用于适配新固件；APK 本身不抓包
- **离线缓存**：保留上次扫描结果与设备清单，重新打开应用即可查看
- **滚动更新**：GitHub Actions 构建签名的 canary APK，应用内检查、下载、校验并安装

最低支持 Android 8.0（API 26）。

## 界面

| 页面 | 内容 |
|---|---|
| 拓扑 | 扫描进度、在线设备与已登录路由统计、可平移缩放的拓扑画布 |
| 设备 | 按名称 / IP / MAC / 厂商搜索，筛选全部、在线、离线、路由器 |
| 路由器 | 列表与登录状态；「添加」和「编辑」都打开独立整页表单，用下拉框选择识别方式，可填写 LAN MAC、账号与备注；点按卡片立即登录并刷新 |
| 设置 | 主题、手动网段、深度扫描、离线设备、Root ARP、探测超时、更新渠道 |

点按拓扑或设备列表中的任意节点会打开详情页：设备显示身份证据（MAC、厂商、端口、服务、发现方式），可以添加或清除本机备注、扫描 1–65535 全部端口，并提供「编辑上级路由凭据」入口；路由器显示固件、运行时间、WAN 上联与客户端列表，可直接在该页登录刷新，也可以跳到自己的凭据编辑页。未登录的网关同样会提供「填写账号」入口。路由器和设备节点都能从这里一步进入对应的路由器编辑页，不再出现只能看不能改的死角。

设备备注以 MAC 为主要键（MAC 不可用时退回 IP），只保存在本机。备注会在加载快照、完成扫描和单独登录路由器之后重新应用，因此改备注不需要重新扫描整个网段。

## 路由器适配与扩展

`router/RouterAdapters.kt` 是唯一的适配器注册表。新增品牌只需要：

1. 实现 `RouterAdapter`（`scan(config, timeoutMs)` 返回统一的 `RouterScanOutcome`）；
2. 把它加入 `RouterAdapters.all`。

路由器编辑页的下拉框、自动识别探测顺序和应用内登录都会自动包含新适配器。`AUTO` 会依次尝试所有适配器，登录页指纹（`RouterFingerprintProbe.detectBrand`）只用于把更可能的品牌排到前面，猜错只会多几次请求，不会导致登录失败。

网页管理类品牌共用一个引擎（`router/WebUiAdapter.kt`）和解析层（`router/WebUiParsing.kt`）：适配器只描述登录页、登录请求与客户端/状态页，引擎负责 Cookie、令牌、表单/JSON 提交和失败判断。解析层同时接受 HTML 表格、直接返回的 JSON，以及脚本里内嵌的 JSON（例如 `var devicelist = {...}`），不依赖固定列号，因此固件换皮后通常仍能读到客户端。品牌清单在 `router/WebUiAdapters.kt`。

两个家用网关已经按实际抓包单独实现，不再走通用描述：

- **中兴 E1600**（`router/ZteAdapter.kt`）：先从首页取出 `_sessionTOKEN` 与 Cookie，再向 `logintoken_lua.lua` 取一次性盐值，最后提交 `sha256(密码 + 盐值)`；成功后读取 `Query_AccessDevice_lua.lua` 的 2.4G / 5G / LAN 三组客户端，以及 `ManagReg`、WAN、DHCP、无线状态页。旧固件的 `GetRandCount.asp` + base64 `/login.gch` 流程保留为兜底。
- **华为 HS8145C**（`router/HuaweiAdapter.kt`）：先提交登录页里的隐藏 `token` 取得登录表单，再用 `username` / `psd` 登录（依次尝试已保存账号、`useradmin`、`telecomadmin`）；客户端来自 `devInfo?type=0`（有线）与 `type=1`（无线），名称来自 `allInfo`，型号、固件与 MAC 来自 `gwinfo`，运行时间来自 `gwstatus`。

路由器凭据优先按 LAN MAC 匹配，其次才比对地址；登录成功学到的新 MAC 会写回配置。这样 DHCP 重新分配地址、或多级路由换了 WAN 口地址时，账号仍然跟着设备走。

## 主机侧抓包

APK 不记录任何 HTTP 流量。需要适配新固件时，在电脑上运行 `tools/router-capture.py`：它通过 Chrome DevTools Protocol 附加到一个可见的 Chromium 窗口，用户在浏览器里正常登录并打开设备列表，脚本记录完整请求与响应（含响应体）并写成 JSON。

```bash
# 1. 用一个单独的用户目录启动 Chromium 并开放 CDP 端口
chromium --remote-debugging-port=9222 \
    --user-data-dir=/tmp/netdis-cdp http://192.168.1.1/

# 2. 另开终端运行抓包，按提示在浏览器里手动登录
python3 tools/router-capture.py \
    --router huawei=华为网关@http://192.168.1.1/ \
    --router zte=中兴E1600@http://192.168.2.1/ \
    --out router-capture
```

- 脚本只依赖 Python 3 标准库，不需要 `pip install`。
- 每个路由器输出一份 `router-capture/<name>/capture.json`，同时保存登录成功页的 `page.html` / `page.png`，便于直接比对字段。
- 抓包文件可能包含会话令牌和客户端信息，`.gitignore` 已忽略 `router-capture/`；提交前请自行脱敏。
- 新流程验证通过后，把对应的解析函数补上单元测试，再把适配器加入 `RouterAdapters.all`。

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
- 应用不记录 HTTP 流量；主机的抓包工具由用户主动运行，输出只留在本机。
- 应用不申请位置权限；Wi-Fi 名称等信息来自系统网络能力接口。

## 已知限制

- MikroTik 需要 RouterOS 7 并启用 `www` / `www-ssl` REST 服务。
- OpenWrt 需要可访问的 `ubus` 端点与 LuCI 账号；部分裁剪固件可能未安装 `luci-rpc`。
- 网页管理类品牌（TP-Link、水星、华硕、小米、腾达、爱快、锐捷、H3C、Netgear、D-Link）的固件差异很大；适配器已覆盖常见登录流程、HTML 表格与 JSON/内嵌 JSON 客户端列表，但个别固件可能只能登录、读不到客户端，可用 `tools/router-capture.py` 抓到实际请求后调整。中兴 E1600 与华为 HS8145C 是按真机流程单独适配的，其他型号仍可能不同。
- 部分新固件要求密码预哈希、图形验证码或多步令牌交换；适配器会尝试常见方案，失败原因会显示在路由器卡片上，主机侧抓包可以看到具体是哪一步被拒绝。
- 全端口扫描会发出 65535 次 TCP 连接，在 Wi-Fi 上可能持续十几分钟并增加耗电；扫描期间应用会以数据同步类型的前台服务保持运行，切到后台不会中断。
- 未单独适配的品牌仍会通过登录页指纹显示品牌并作为网关出现在拓扑中；新增品牌按「路由器适配与扩展」接入。
- 蜂窝网络下运营商通常隔离局域网，扫描结果可能为空。
- 拓扑布局针对家庭网络规模优化，超大网段会限制扫描地址数量。
- 无锁屏密码本身不影响凭据加密；但如果 Keystore 报 `-74 ATTESTATION_KEYS_NOT_PROVISIONED`（常见于 root 或定制 ROM），硬件密钥无法生成，此时密码由软件密钥加密。

## 代码结构

```
app/src/main/kotlin/com/geno1024/ai/netdis/
├── model/      共享数据模型
├── net/        地址、ARP、DNS、mDNS、SSDP、NetBIOS、端口探测与全端口扫描
├── router/     适配器注册表、OpenWrt / MikroTik / 中兴 / 华为 / 网页品牌适配器与 HTTP 会话
├── scan/       前台全端口扫描服务与通知
├── topo/       拓扑构建与布局
├── storage/    Keystore 加密、设置、设备备注、路由器与快照持久化
├── update/     canary 清单、镜像、下载校验与安装 Intent
└── ui/         Compose 单 Activity 界面
```

`tools/router-capture.py` 是仓库中唯一的主机侧工具，用于通过 CDP 记录路由器网页登录流程；它不参与 APK 构建。
