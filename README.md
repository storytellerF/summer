# Summer

Summer 是一款 Android 应用，用于记录资金来源的余额快照，并从截图导入真实交易。图像导入可使用已安装的 LLMD 应用，也可通过 Koog 调用支持图像的 API 模型。

主页时间线随滚动加载余额快照和导入的交易。每份快照显示当时的资产总额和各账户余额。交易保留识别得到的金额。同一账户两次余额记录之间的订单用于解释余额变化；未被解释的部分显示为 **Balance difference**。例如，余额减少 100、订单合计为 −70 时，会显示这些订单和 −30 的差额。变化全部被解释时，不显示差额行。后续导入会自动更新剩余差额。

点击主页工具栏的 **Import orders**，选择账户，再点击 **Choose Screenshot**。检查紧凑的交易列表，点击交易可编辑日期、以账户货币计价的正负金额、说明和原始交易 ID。取消勾选不需要的行，再点击 **Import selected transactions**。截图识别与余额识别使用相同的提供方设置。请使用收支方向清楚的截图。订单日期来自截图中的交易文字，与图片创建时间无关。不完整或相对日期会留空，必须在导入前补齐；金额含糊、待处理交易和明确使用不同账户货币的行会被跳过。

可见的交易、订单或参考 ID 用于避免同一账户重复导入交易，也能跨截图去重。没有可见 ID 时，预览会明确提示：此时只能检测同一截图行的重复导入。裁剪或重叠且没有 ID 的截图可能包含重复交易，请在导入前取消勾选。日期按设备本地时区解释。成功识别的截图以压缩 JPEG 保存到应用私有存储，并关联到保存的余额或交易记录。同一截图的交易共用一个图片文件。导入交易不会改变已记录的余额快照。

通过 **Record balance → Manual** 录入账户余额、记录时间和可选备注。保存前可编辑 **Local date and time**，其默认值为当前时间。选择图片时，如可读取原始拍摄或创建元数据，则使用该时间；无法读取创建元数据的图片默认使用当前时间。保存历史截图前，请检查或修正时间。

余额记录的整体时间跨度在 **10 分钟**以内，且同一账户的重复读数余额相同时，会显示为同一个时间线节点。余额变化会开始新节点；时间跨度内的订单不会拆分节点。原始读数及其图片链接仍独立保存。

通过 **Record balance → Screenshots** 选择账户，并为每个账户指定要读取的余额标签，例如可用现金或储蓄余额。使用 **Choose images** 选择一张或多张图片；一张图片可以包含多个账户余额。统一检查全部结果，点击余额可分配账户或编辑金额、时间和备注；取消勾选不需要的行，再点击 **Save selected balances**。两种导入流程都支持批量选择，放弃当前预览并选择其他图片前会要求确认。每张图片提供自己的创建时间和压缩图片链接。另一张图片失败时，已完成的结果仍可查看；所选结果以原子方式保存。

通过 **Settings → Accounts** 管理资金来源。

打开 **Settings → Image recognition**，可选择 **LLMD**、**OpenAI**、**Anthropic (Claude)**、**OpenRouter** 或 **OpenAI-compatible** API 提供方。默认选择 LLMD。

使用 API 提供方时，输入 HTTPS **API base URL**（包含版本路径，例如 `https://openrouter.ai/api/v1`）、支持图像和 JSON Schema 的 **model ID** 及 **API key**，再点击 **Save recognition settings**。OpenAI 连接可使用 Responses API 或 Chat Completions。API 提供方必须支持原生 JSON Schema 输出；模型不支持时会失败，不回退到纯文本。由于已安装的 Gemma 模型在 LiteRT-LM 引导生成模式下会省略可读取的值，LLMD 使用仅输出 JSON 的提示词，并由应用严格验证。格式错误或不完整的结果会被拒绝。本版本支持 API key，不包含账户或订阅登录。

每个提供方独立保存连接。API key 在设备上加密，并排除在备份之外。**Remove saved connection** 会删除对应提供方的连接；如果它当前处于启用状态，则切回 LLMD。启用 API 连接时，导入图片会上传到所选远程提供方。保存前请检查识别金额和交易标识。

可从 OpenRouter 的 `qwen/qwen3.8-27b:free` 开始测试。可用性、图像支持、免费额度和账户权限可能变化，请查看提供方当前的模型目录。

使用 LLMD 时，在 **LLMD build** 中选择已安装的包：

- Release：`com.storytellerf.llmd`
- Alpha：`com.storytellerf.llmd.alpha`
- Debug：`com.storytellerf.llmd.debug`

每个资金来源都有 ISO 货币代码，可在 Settings → Accounts 编辑，已有账户默认为 CNY。识别遵循所选账户货币，不进行换算。时间线按币种分别显示总额。

选择会保存在设备上。Debug 构建默认使用 LLMD Debug，非 debug 构建默认使用 LLMD Release，直到另行选择。请在 LLMD 中选择已导入模型并开启模型服务，等待 Ready 后再识别图片。Summer 使用该服务的当前活动模型，绝不会选择默认模型或回退到其他模型。服务为 Off 时，请打开 LLMD 启用。Settings → Image recognition 分别显示 Release、Alpha 和 Debug 的安装状态、当前 Summer 应用的授权、服务状态、运行中的模型及已导入模型。按需使用各构建的 Authorize/Open 按钮。页面可见时及从 LLMD 返回后会刷新状态。旧版 LLMD 如果会在状态查询期间加载模型，则显示更新提示，将授权和模型信息标为未知。

Debug、alpha 和 release 构建可同时安装，各自独立保存余额、交易、图片和识别设置。

构建说明及可选的 OpenRouter 图像测试见 [DEVELOPMENT.md](DEVELOPMENT.md)。
