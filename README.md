# Summer

An Android app for recording fund-source balance snapshots and importing real transactions from screenshots. Image import can use an installed LLMD app or an image-capable API model through Koog.

The home timeline loads balance snapshots and imported transactions as you scroll. Each
snapshot shows the total assets and account balances at that moment. Transactions retain
their recognized amounts. Orders between two balance records for the same account explain
that balance change; any remaining amount appears as a **Balance difference**.
For example, a balance drop of 100 with orders totaling −70 shows those orders and a −30
difference. Fully explained changes have no difference row. Later imports update the
remaining difference automatically.

Tap **Import orders** in the home toolbar, choose an account, then **Choose Screenshot**.
Review the compact list and tap a transaction to edit its date, signed CNY amount, description
and original transaction ID.
Uncheck rows you do not want and tap **Import selected transactions**. Screenshot recognition
uses the same provider settings as balance recognition. Use screenshots with clear income/expense direction.
Incomplete or relative dates are left blank for you to fill before importing; ambiguous
amounts, pending transactions and non-CNY rows are skipped.

Visible transaction/order/reference IDs are used to avoid importing the same transaction twice
for an account, including from different screenshots. When an ID is not visible, the preview
says so: only repeated imports of the same screenshot rows can be detected. Cropped or overlapping
screenshots without IDs may contain duplicates; uncheck them before importing. Dates are interpreted
in the device's local time zone. Successfully recognized screenshots are saved as compressed JPEGs
in the app's private storage and linked to the saved balance or transaction records.
Transactions from the same screenshot share one image file. Importing transactions does not change recorded balance snapshots.

Use **Record balance → Manual** for an account balance, recording time and optional note.
Edit **Local date and time** before saving. It defaults to the current
time. Selecting an image uses its original capture/creation metadata when available;
images without readable creation metadata default to the current time. Check or correct
the time for historical screenshots before saving.

Balance records within a total span of **10 minutes** appear in one timeline node when
repeated readings of the same account have the same balance. A different balance starts a
new node; orders can appear within the time span without splitting it. Original readings
and their image links remain saved independently.

Use **Record balance → Screenshots**, select the accounts and specify the balance label to
read for each account (for example, available cash or savings balance). Use **Choose images**
to select one or several pictures; a picture can contain multiple account balances.
Review all results together. Tap a balance to assign its account or edit its amount/time/note,
and uncheck unwanted rows before **Save selected balances**. Both import flows offer bulk
selection and confirm before discarding a preview to choose other images.
Each picture supplies its own creation time and compressed image link. Completed results
remain available if another picture fails; saving selected results is atomic.

Manage fund sources under **Settings → Accounts**.

Open **Settings → Image recognition** to choose **LLMD**, **OpenAI**, **Anthropic (Claude)**,
**OpenRouter**, or an **OpenAI-compatible** API provider. LLMD is selected by default.

For an API provider, enter its HTTPS **API base URL** (including the version path, such as
`https://openrouter.ai/api/v1`), an image-capable **model ID**, and your **API key**, then tap
**Save recognition settings**. OpenAI connections can use Responses API or Chat Completions.
This version supports API keys; account/subscription login is not included.

Each provider keeps its own saved connection. API keys are encrypted on the device and excluded
from backups. **Remove saved connection** erases a provider's connection and returns to LLMD if
that provider was active. Imported images are uploaded to the selected remote provider when
an API connection is active. Review recognized amounts and transaction identifiers before saving.

OpenRouter's `qwen/qwen3.8-27b:free` is a starting model for testing. Availability, image
support, free quotas, and account permissions can change; check the provider's current catalog.

For LLMD, choose the installed package in **LLMD build**:

- Release: `com.storytellerf.llmd`
- Alpha: `com.storytellerf.llmd.alpha`
- Debug: `com.storytellerf.llmd.debug`

The choice is saved on device. Debug builds default to LLMD Debug; non-debug builds default to
LLMD Release until a different package is selected.

Debug, alpha and release builds can be installed together. Each keeps its own balances,
transactions, saved images and recognition settings.

See [DEVELOPMENT.md](DEVELOPMENT.md) for build instructions and the optional OpenRouter vision test.
