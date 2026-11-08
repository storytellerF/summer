# 开发指南

使用 JDK 17、Android SDK platform 37，以及 Android 模拟器或 USB 连接的实体设备。Gradle Wrapper 会下载所需 Gradle 版本。

Ktor 3.6 使用 OkHttp 5.5，其 Android 构件要求 compileSdk 37。Ktor BOM 将 Koog 间接依赖的 content-negotiation 和 serialization 构件与传输层版本对齐。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

```sh
./gradlew :app:connectedDebugAndroidTest
```

Windows 使用 `gradlew.bat`。为 Gradle 选择设备时设置 `ANDROID_SERIAL`，并将相同 serial 传给 Python 执行器。整个 UI 测试期间保持实体设备解锁且唤醒：休眠或锁屏会阻止 Compose 生命周期收集器运行。测试后恢复临时保持唤醒设置。设备套件安装独立 Debug 应用，并重置其测试数据库；使用没有重要 Debug 数据的设备。

## Release 构建弃用检查

Release 工作流会分别输出 `assembleAlpha`（分支）和 `assembleRelease`（tag）的 Gradle 警告。本地复现两条构建路径：

```sh
./gradlew assembleAlpha assembleRelease --warning-mode all --stacktrace
./gradlew assembleAlpha assembleRelease --warning-mode all
```

首次运行必须配置项目，才能诊断插件调用。如果已有配置缓存，可在诊断运行中使用 `--no-configuration-cache`，随后再次运行上面的命令，检查缓存保存和复用。`--stacktrace` 包含弃用 API 的调用方。不要在发布工作流中压制警告或关闭配置缓存来隐藏插件问题。

针对 [issue #23](https://github.com/storytellerF/summer/issues/23)，Gradle 9.8.1 与 AGP 9.1.1 在 `VariantDependenciesBuilder.build` 复现了 `dependency_project_notation`：AGP 将 `Project` 作为依赖声明传入，这在 Gradle 10 中会成为错误。升级到 AGP 9.4.1 后该警告消失；上游 main 已包含此升级。PR 保留上游的 AGP 9.4.1 和 Gradle 9.8.2，不重复或回退依赖更新。这些构建没有发现项目脚本调用弃用 API。

剩余的 `Configuration.setVisible(boolean)` 警告针对 **Gradle 11**，不是 10：

| 归属／版本 | 诊断栈中的调用方 | 后续处理 |
| --- | --- | --- |
| AGP 9.4.1 | `BasePlugin`（JDK image、lint 和 Android test 配置）、`SourceSetManager.createConfiguration`、`TaskManager.createCoreLibraryDesugaringConfig`、`VariantDependenciesBuilder.build`、`BaseJavaPreCompileTaskCreationConfig.createKaptOrKspClassPath`、`UtpTestUtilsKt.maybeCreateUtpConfigurations` | 升级 AGP 时重新检查；9.4.1 仍包含这些调用。 |
| KSP 2.3.12 | `KspConfigurations` 构造函数和 `createConfiguration`、`KspSubpluginKt.markResolvable` | [KSP #3234](https://github.com/google/ksp/issues/3234)，由 [#3237](https://github.com/google/ksp/pull/3237) 修复；修复已合并，但未包含在 2.3.12 中。有包含修复的版本后升级。 |

AGP 9.4.1 下，Alpha（启用缩减）和 Release APK 组装均通过，重复构建复用了配置缓存。在上游 Gradle 9.8.2 上也通过两种构建及全部 68 个 `testDebugUnitTest` 测试；`lintDebug` 完成，结果为 0 个错误和 13 个警告。APK ZIP 完整性检查通过，包括 manifest 和 DEX 条目。缺少 release 签名变量时，本地验证生成未签名 APK，不验证生产密钥库，也不发布版本。更新插件时，除了警告文字，还要持续检查 APK 输出和缓存行为。

标准 instrumentation 套件使用模拟 HTTP 传输层，覆盖财务、识别设置及 Koog 从图像到表单再到数据库的路径。真实 OpenRouter 测试只有通过下方执行器显式准备后才会运行，否则跳过。

## OpenRouter 图像测试

通过环境的机密配置，将 `OPENROUTER_API_KEY` 设置到测试进程环境。不要将其写入 Gradle properties、源码、instrumentation 参数、shell 历史或 APK。连接设备后运行：

```sh
python3 scripts/test-openrouter.py --serial emulator-5554
```

机密使用其他环境变量名时，传入 `--key-env VARIABLE_NAME`。执行器检查 OpenRouter 当前模型目录，选择支持图像输入、结构化输出，且 prompt/completion 价格均为零的 `:free` 模型。`--model MODEL_ID` 可选择其他符合条件的模型；不会自动切换到付费模型。免费服务仍可能要求账户权限，并有速率或容量限制。

测试真实应用截图时，将公开的开发者演示图片下载到仓库之外，人工核查余额，再同时提供图片和预期金额：

```sh
python3 scripts/test-openrouter.py --serial emulator-5554 \
  --image /tmp/screenshot.jpg --expected-balance -41.54 --currency USD
```

例如，[Cashew 开发者网站](https://cashewapp.web.app/) 提供的[主页截图](https://cashewapp.web.app/assets/screenshots/home-promotion-light.jpg)中，所选 USD Wallet 余额为 `-41.54`。不要将截图和个人财务信息放入提交的夹具。原图传入设备后，由生产图像编码器处理；测试根据提供的预期验证余额，结束后删除临时截图。

已在 Android 35、Koog 1.3.0 和 `qwen/qwen3.8-27b:free` 上验证：

| 公开截图 | 预期余额 | 识别、表单与数据库 |
| --- | ---: | --- |
| 上面链接的 Cashew 主页截图 | -41.54 | 通过 |
| [产品更新](https://monzo.com/blog/2016/08/08/updated-design)中的 [Monzo 产品截图](https://www.monzo.com/static/images/blog/2016-08-04-updated-design/design.png) | 1031.25 | 通过 |

验证期间，真实服务偶尔返回上游 HTTP 429。错误报告不会暴露响应正文或凭据；容量可用时可重试此可选测试。标准套件运行离线设备回归测试；未准备的真实服务和外部截图测试会跳过，由脚本单独执行。

执行器构建并安装 debug APK，通过 stdin 将 key 写入应用私有临时文件，再调用真实 instrumentation 测试。测试生成合成负余额图片，通过生产编码器和 Koog 客户端，调用图片选择器使用的同一 ViewModel 操作，检查渲染金额，再经表单保存到 Room。系统图片选择器导航不在此测试范围内。测试恢复原提供方配置并移除夹具；执行器还会在 `finally` 中删除临时 key 文件和截图，包括失败情况。

两个 Python 执行器会自动使用 Windows Gradle Wrapper。两个 Debug APK 都已构建时，可使用 `--skip-build`。测试账户默认使用 CNY；通过 `--currency` 指定截图账户货币，例如 Cashew 用 USD，Monzo 用 GBP。模型必须当前支持图像和原生 JSON Schema；有免费模型并不意味着支持 schema。截图通过 ADB stdin 以 base64 传输，并在设备上核验 SHA-256，避免 Windows 二进制 stdin 损坏。凭据不会进入命令参数或日志。设备没有互联网时，可通过 `adb -s SERIAL reverse tcp:PORT tcp:PORT` 转发外部 HTTP CONNECT 代理，再向任一执行器传入 `--proxy-port PORT`。这只改变 instrumentation 测试传输层，保留正常 TLS 和证书验证；测试后必须移除 ADB reverse 映射并停止代理。

## 识别架构

`ConfiguredImageAnalyzer` 为每个请求保存识别设置快照，再分派给 LLMD 适配层或 `KoogImageRecognizer`。现有 LLMD 目标流仍决定哪个已安装包接收 IPC 和授权请求。两条路径共用 `RecognitionImageEncoder`，限制图片解码规模，缩放到 1600 像素，并将 JPEG 负载限制为 500 KB。Koog 在 Android 上显式使用 Ktor OkHttp 传输层；每个请求后关闭客户端和传输层，并传播取消。现在只保留多余额和交易识别接口；旧的单值余额接口及零值回退已移除。两个后端共用英文提示词和结构 schema。Koog 通过 `LLMParams.Schema.JSON.Standard` 发送原生 JSON Schema：Chat Completions 使用严格的 `response_format`，Responses 使用 `text.format`，Claude 使用 `output_config.format`。OpenRouter 路由要求支持相应参数。Schema 不受支持时直接失败，不用文本重试。由于 Claude 不支持 schema 的 `maxItems`，数组行数上限仍由解析器检查。

`RecognitionSettingsHost` 独立于 Compose 管理设置状态和操作，在注入的串行调度器上协调，在 IO 上持久化。余额录入 Host 也在此调度器上协调状态，而识别在 IO 执行。API key 使用 Android Keystore 密钥进行 AES-GCM 加密。Preferences 保存在 `noBackupFilesDir`，API 请求、响应正文和凭据绝不写入应用日志。

## 录入与审核 UI

时间线使用一个主要的 **Record balance** 操作和带标签的 **Import orders** 操作。最新余额节点显示当前资产总额，旧检查点和单笔订单构成历史。未覆盖差额使用独立标记和解释标签。余额录入通过 `AddBalanceChangeHost` 管理显式的 Manual/Screenshots 模式。手动草稿在模式切换后保留，但截图模式不能保存过时的手动草稿。

两种截图导入都使用选择和审核步骤、紧凑的可选择行、批量选择，以及 `ui/components` 提供的底部弹层详情编辑器。行编辑更新 Host 草稿；关闭详情弹层不会保存数据库。保存时验证并持久化所选记录。余额重新分配账户时，只能选择预选目标。订单预览的账户保持固定，直到用户确认放弃该预览。放弃时清除待识别引用和草稿图片元数据，不删除已保存数据。

设置将 Accounts 与 Image recognition 分开。只有 LLMD 显示构建选择器；API 提供方显示各自连接字段。生产 UI 使用已有 Material 3 主题。Compose 状态和 Paging 收集器显式使用 `Dispatchers.Main.immediate`；Host 协调、识别和数据库工作仍使用后台调度器。`ImportReviewJourneyTest` 覆盖日期修正、详情、取消选择和持久化；`BalanceScreenshotImportTest` 通过详情编辑器测试含糊的账户分配。向这些设备测试传入 `-e design_screenshots true`，可将合成流程截图保存在 debug 应用的 `cache/design-screenshots/` 供视觉审查。此辅助程序不会捕获用户截图或凭据。

## 交易导入与时间线分页

余额录入 Host 管理可编辑的本地日期时间，持久化为 `BalanceChange.timestamp`。注入的时钟初始化表单；注入的 `ImageCreationTimeReader` 在识别或重新编码之前，通过 IO 读取原始 URI 元数据。Android 优先使用 MediaStore DATE_TAKEN（包括媒体 URI 映射），以及 EXIF original 或 digitized 日期，支持时区偏移和亚秒。不能将加入图库时间或修改时间视为创建时间；缺少元数据时使用当前时间。元数据等待期间的手动修改，以及授权重试前的修改都予以保留。回填历史时间保存时，使用账户在该时间之前的余额，而非最新余额。

`ImportTransactionsHost` 在串行协调调度器上管理识别、LLMD 授权重试、可编辑预览、选择和批量持久化。图像识别与数据库工作在 IO 上执行；解析和预览准备在工作调度器上执行。Koog 请求原生 JSON Schema；LLMD 通过文本生成模式请求 JSON。两者共用严格的交易数组解析器，字段包括本地 ISO 日期、所选资金来源货币的正负金额、备注和可空原始交易 ID。解析器接受裸 JSON 对象或包裹对象的一层 JSON 代码围栏；拒绝周围说明文字和格式错误的负载。完整本地日期可省略前导零，但必须包含年、月、日、时、分、秒，并通过严格日历验证。新请求将截图字面日期复制到 `dateText`，将 `timestamp` 保持为 null。应用解析完整数字日期、中文日期和英文月份日期；相对或不完整日期依据仍为 null，即使模型另外提供了编造的 timestamp。该字段是临时字段，不改变数据库。旧的五字段响应和夹具仍可读取。缺失或相对日期在识别中保持 null，保存前必须在预览补齐。缺失 ID 保持 null，绝不根据日期或金额合成。余额和交易识别都通过 `FileRecognitionImageStore` 将准确的压缩 JPEG 保存到 `filesDir/recognition-images`。按内容寻址的文件名复用相同图片；临时文件原子重命名防止出现部分图片文件。实体的 `imagePath` 保存相对于 `filesDir` 的路径（用 `File(context.filesDir, path)` 解析），因此应用数据迁移后仍有效，不依赖图片选择器 URI 授权或缓存寿命。手动余额和已有记录的图片路径为 null；识别成功后，即使随后取消预览，也保留图片。LLMD 临时共享图片仍会在 IPC 完成后撤销授权并删除；保留图片仍是私有的。

Room version 2 从 version 1 迁移时，不删除余额，也不生成交易。Room 通过 `@Database(autoMigrations = [AutoMigration(from = 1, to = 2)])` 生成 SQL，builder 自动注册此迁移。Room Gradle 插件按 build type 分别配置 KSP schema 导入和导出：

| 构建类型 | Application ID | Schema 目录 |
| --- | --- | --- |
| debug | `com.storytellerf.summer.debug` | `app/schemas/debug/com.storytellerf.summer.data.db.SummerDatabase/` |
| alpha | `com.storytellerf.summer.alpha` | `app/schemas/alpha/com.storytellerf.summer.data.db.SummerDatabase/` |
| release | `com.storytellerf.summer` | `app/schemas/release/com.storytellerf.summer.data.db.SummerDatabase/` |

这些应用可以同时安装，数据库、识别设置和保留图片相互独立。namespace 以及 Kotlin 和测试类包名仍为 `com.storytellerf.summer`。Debug instrumentation 安装为 `com.storytellerf.summer.debug.test`；两个截图执行器都以 debug 应用为目标。使用原 application ID 的已有安装保留数据，新 debug 应用从独立空存储开始。所有构建类型的数据库版本都保持 2。

实体变化时一起提交生成的 schema。Version 1 从隔离检出中未修改的基线数据库和实体导出，应保留这一历史 schema。Version 2 由本 PR 当前实体生成。Schema 变化后，重新构建 `:app:kspDebugKotlin :app:kspAlphaKotlin :app:kspReleaseKotlin`，使所有构建类型导出当前 schema。编辑实体和注解，不编辑生成的 JSON 或 SQL。本 PR 中全部 schema 变化共用 version 2。如果开发安装使用了同一 PR 的早期 schema，测试最新 APK 前应清除应用数据，而非增加数据库版本。此操作会重置本地数据和识别设置：

```sh
adb -s emulator-5554 shell pm clear com.storytellerf.summer.debug
```

`BalanceImpactRecord` 是独立实体，具有资金来源外键，以及 `(fundSourceId, transactionId)` 和 `(fundSourceId, imageDedupKey)` 唯一索引。只有缺少完整原始 ID 的行，才获得由 `imageHash:imageRow` 组成的图片去重键；有 ID 的交易按 ID 去重，即使识别改变其列表位置。遮盖或截断的 ID 保持 null。编码截图的 SHA-256 摘要用于检测重复图片。批量插入使用事务并忽略重复；删除账户级联删除其交易。

每个 `BalanceChange.coveredOrderAmount` 持久化该账户在 `(前一快照时间戳, 当前快照时间戳]` 内的有符号订单合计。时间戳相同时按 ID 排序，较后的同时间快照区间为空。第一份快照没有区间，覆盖额为零；快照区间外的订单仍独立可见。迁移 1→2 将覆盖额初始化为零，因为 version 1 没有导入订单。导入和快照插入、更新或删除会在同一 Room 事务中重算受影响账户，快照换账户时也包括旧账户。调用方必须通过 `DataRepository` 写入，以维持此不变量。订单的 `(fundSourceId, timestamp)` 索引限制区间聚合范围。覆盖额是净额，不是数量或绝对值之和，也不以观察到的余额变化为上限。时间线从相邻真实账户余额变化中减去持久化覆盖额（初始旧记录回退到 `previousBalance`）。差额按资金来源货币的小数位数舍入，为零时隐藏；支出超过余额变化时可产生正差额。

`groupBalanceRows` 按从新到旧划分读数。每组整体时间戳跨度最多 600,000 毫秒，重复资金来源 ID 的余额必须相同。订单不参与分组。`timeline_balance_groups` 是可重建索引，每个原始 `BalanceChange.timelineGroupId` 指向所属组。组 ID 使用最小成员 ID，新增较新的重复读数时保持不变。余额或账户变更在同一事务重建索引，迁移后首次读取页面时补齐未分组记录。原始记录、图片路径和覆盖额均保留。

`FeedPagingSource` 每页读取 20 个完整余额组，按最新时间戳和记录 ID 排序。即使某组超过 20 条读数，也不拆组。仓库在同一 Room 事务中读取组成员、各账户前序余额、资金来源和交易。交易窗口以组开始时间为边界，下界包含、上界不包含；首尾窗口包含最新或最旧读数之外的订单。刷新时若旧 offset 超过当前组数，则夹取到最后可用页。没有余额读数时，直接按交易行分页。分页键计算组数，不计算展开后的行数。UI 每组输出一份快照、独立交易，以及每个账户／组的一项未覆盖差额汇总。快照状态遵循全部原始读数，账户订单覆盖额仍根据原始账户区间计算。

`AddBalanceChangeHost` 在顺序识别最多 20 张图片前，冻结所选账户 ID 和要求读取的余额标签。每张图片默认使用自己的创建时间；未知账户 ID 在预览中保持未分配。LLMD 和 Koog 都使用严格的余额数组，账户为已选 ID 或 null，每张图片共用一份保留 JPEG。授权后从当前图片恢复，不重复已完成行。图片失败时，已完成行仍可审核。余额标签跨语言按含义匹配，例如 Account balance 对应余额；缺少平台名时生成未分配行，而非丢弃可读取余额。两套提示词均为英文。每个目标携带 ISO 货币代码；明确不匹配的结果不转换而直接排除，缺失或含糊的符号仍可审核。自动 1→2 迁移时，FundSource.currency 默认为 CNY。快照总额按货币分组；混合货币快照没有合并总额。LLMD 服务失败使用分类消息，不暴露模型路径或响应内容，并与空结果区分。预览支持账户分配、金额／时间／备注编辑和取消选择。`insertBalanceChanges` 先验证全部所选行，再原子插入、重算受影响账户覆盖额并重建组。外键或其他写入失败回滚整批。本 PR 的数据库版本保持 2。

Host 在整个生命周期保留一个 Pager，任意时间线表变化时，使活动 PagingSource 失效。源创建和失效共用串行协调调度器，观察器随 Host 取消。刷新使用前次分页状态的锚点，不从 offset 零重新开始。PagingData 在 ViewModel 生命周期内缓存。

Compose 时间线在分页行前放置固定 key、不可交互的 0dp sentinel。它属于 UI 结构，不加入 `TimelineItem`、数据库记录或 PagingSource 键。Sentinel 占用 LazyColumn 索引 0；分页 `items` 块仍从零索引 PagingData，因此外部按列表位置滚动时必须计入这一行 UI 前缀。

单元测试覆盖提供方请求、解析、预览与选择、授权、取消和分页。`TransactionDatabaseTest` 在设备上覆盖 Room 迁移、跨图片 ID 去重、账户删除、区间边界和历史账户余额初始值。

## 公开交易截图回归

下载的截图和人工核查的预期 JSON 应保留在仓库之外。标准套件跳过 `TransactionScreenshotImportTest`，除非执行器提供图片和预期行。例如，[支付宝官方帮助页](https://cschannel.alipay.com/mobile/helpDetail.htm?help_id=201602058759)包含一张[交易详情截图](https://tfsimg.alipay.com/images/cspropmng/TB1Qx0_X7RDDuNkUvNm760SypXa)，显示 `-20.70`、`2018-11-05 10:59` 和遮盖的 ID。其预期 JSON 为：

```json
{"transactions":[{"timestamp":"2018-11-05T10:59:00","amount":-20.70,"note":"饿了么","transactionId":null,"currency":"CNY"}]}
```

[支付宝列表截图](https://tfsimg.alipay.com/images/cspropmng/TB1lwuCXJC2aKRkUvMH761PkFXa)使用相对日期，没有绝对参照；[Sylq 演示交易列表](https://wiki.sylq.io/img/merchant/ListTransactions.png)使用欧元。支付宝列表应保留五笔带符号金额的交易，`timestamp:null`；测试验证预览日期未补齐前禁止持久化。测试输入的合成日期用于验证手动修正，不声称来自截图。Sylq 欧元列表使用 `{"transactions":[]}`，预期所选 CNY 账户拒绝这些结果。遮盖的标识绝不能用作去重 ID。

模拟器启动后运行：

```sh
python3 scripts/test-transactions.py --serial emulator-5554 \
  --image /tmp/alipay-detail.png --expected-json /tmp/alipay-detail.expected.json
```

执行器构建 APK，按需安装，通过 stdin 将外部夹具传入应用私有、不参与备份的文件，再运行截图测试并移除夹具。APK 已构建时使用 `--skip-build`。使用 `--currency ISO_CODE` 验证其他账户货币；默认 CNY 账户有意拒绝 Sylq 欧元夹具。空结果预期必须由解析器的识别拒绝满足；网络、提供方和图片解码失败必须让测试失败，不能算作成功的货币过滤。外部截图测试整体允许 180 秒，覆盖生产客户端 90 秒请求限制及 UI 审核和持久化；协程测试默认的 60 秒会过早取消合法但较慢的提供方请求。默认模拟 API 响应使用人工核查的预期 JSON：验证生产编码器、Koog 图像请求、可编辑预览、确认、重复处理、Room 持久化和保留图片字节；这**不能**验证模型识别准确性。

在测试进程环境配置 `OPENROUTER_API_KEY`，再添加 `--live`，可使用相同预期测试真实识别。`--key-env` 指定其他机密变量名；`--model` 选择符合条件的免费图像模型，并根据当前目录验证。Key 通过 stdin 进入应用私有、不参与备份的临时文件，在 `finally` 中移除。

两个执行器默认使用免费模型。显式测试付费模型时，传入 `--model google/gemini-2.5-flash-lite --allow-paid-model`（交易测试还需 `--live`）。执行器仍检查图像和结构化输出支持，不会自动切换到付费模型。

两个执行器都支持 `--llmd debug`（或 `release`/`alpha`），通过 Binder IPC 对已安装 LLMD 模型执行截图、预览和持久化断言。交易执行器使用它替代 `--live`。运行前在该 LLMD 构建中授权 `com.storytellerf.summer.debug`；模型缺失、授权失败或识别错误都会使测试失败。在 LLMD 中显式选择已导入模型并开启模型服务。Summer 使用 Ready 服务实际报告的 `activeModel`，没有硬编码 LLMD 模型、首模型选择或模型回退。服务为 Off 时立即失败，并提示打开 LLMD；绑定和健康检查不能启动引擎。

LLMD 识别在发送图片前保持 Binder 连接并轮询引擎健康。异步模型初始化最多可用 120 秒；初始化错误立即失败。Ready 服务还必须报告非空 `activeModel`；缺少该字段的旧 LLMD 必须更新。

生产 coroutine core/Android 和协程测试构件共用版本目录中的版本，应保持对齐：instrumentation 在应用 APK 的协程运行时上执行，只升级测试构件可能导致测试开始前缺少方法。

页面副作用处理函数将导航、授权启动和通知分派到 Android 主调度器；Host 协调继续在注入的串行工作调度器上执行。

## LLMD 集成验证

`LlmdStatusHost` 在串行调度器上协调各构建的独立快照和启动副作用。`AndroidLlmdStatusReader` 在 IO worker 上读取包元数据、健康状态和模型清单，每个构建使用临时连接。不绑定缺少显式服务控制的旧构建，因为其 health 处理函数可能初始化引擎。授权和模型状态在能安全读取前保持未知。

该区域恢复活动时，每五秒刷新状态，隐藏时停止。取消的读取不能发布过时结果。授权来自真实 Binder 响应，不来自 Activity 结果码。缺少活动模型报告时，绝不选择首个导入模型。测试覆盖构建隔离、取消与恢复、路由和渲染。

用户可见的 Binder 连接使用 `BIND_AUTO_CREATE | BIND_IMPORTANT`，让推理共享前台调用方的优先级。LLMD 自身前台服务控制显式引擎加载和关闭。可能需要通知权限、厂商后台运行或最近任务保护设置；不添加隐式模型重启。

真实检查覆盖 Off/Ready Binder 约定、重复文本生成、合成余额图片识别、预览与持久化，以及引导响应格式。之前的内存压力、OEM 回收和复杂图片准确率失败，仍须与通过的断言区分。普通 LLMD 请求使用仅输出 JSON 的提示词和严格解析；API 提供方继续使用原生 JSON Schema。两者共享提取提示词。

可选探针要求 LLMD 已获授权、处于 Ready，且显式选择了已导入模型。共同使用 `llmd_target=debug` 和 `llmd_active_model=<imported-model-id>`，再按需选择：

- `llmd_image_probe=true`，测试类为 `com.storytellerf.summer.data.llmd.LlmdServiceContractTest#imageProbeUsesTheExplicitActiveModelWithoutLaunchingSummerUi`，验证不启动 Summer UI 的推理。
- `llmd_format_probe=true`，测试类为 `com.storytellerf.summer.data.llmd.LlmdGuidedGenerationTest`，比较图像文本、JSON object 和 JSON schema，并断言纯文本 schema。
- `llmd_service_state=off` 或 `ready`，加 `llmd_model_ids=<comma-separated-imported-ids>`，验证准确模型清单和不替换模型的约定。
- `llmd_text_probe=true`，验证客户端断开连接后的重复文本请求。

后台回归时，显式重新加载所选模型，返回桌面，让模型服务空闲，再执行真实识别、预览、保存流程。确认活动 ID 和服务进程保持不变。记录通知对话框顺序、权限状态、应用设置导航，以及显式关闭和资源释放。

原始日志、设备标识、硬件规格和设备专用内存测量保留在仓库之外。这里只记录通用配置、可复现命令和功能验证结果。模型文件、密钥和个人截图不得提交或打包。
