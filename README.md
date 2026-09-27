# X 垃圾帖屏蔽（LSPosed 模块）

识别 X（Twitter）安卓客户端里 `@某人,内容` 形式且带性暗示的垃圾回复，在帖子末尾显示一个可点击的
**屏蔽** 按钮；点击后把该账号加入屏蔽关键词，其帖子立即从时间线消失。

- 目标：`com.twitter.android` **12.25.0-prod.01**（Android 16 / arm64-v8a）
- 框架：LSPosed v2.1.1（Zygisk），legacy Xposed API
- 全部匹配在设备本地完成，不向任何服务器发送内容

## 功能

| 能力 | 状态 |
| --- | --- |
| 检测 `@handle,` / `@handle，` 且含性暗示的帖子 | ✅ 已实机验证 |
| AI 判定（deepseek-flash，含缓存与异步） | ✅ 已实机验证（需自备 API Key） |
| 在帖子旁显示可点击的「屏蔽」 | ✅ 已实机验证（蓝色链接样式） |
| 点击后写入屏蔽关键词并持久化 | ✅ 已实机验证 |
| 被屏蔽账号的帖子从时间线移除 | ✅ 已实机验证 |
| 同步到 X 服务端「已静音的字词」 | ⚠️ 被 X 的设备证明签名拦下，见下文 |

## 界面

**X 客户端内** —— 帖子末尾出现蓝色「屏蔽」，点击即生效：

```
可能以后都要穿尿布睡觉吧  屏蔽      ← 「屏蔽」为可点击链接
```

**模块设置页** —— Material 3 风格（baseline 配色，跟随系统浅色/深色）：

- 顶部大标题 + 副标题
- 「运行状态」卡片：由 X 进程回报，绿点=已连接（附 pid）/ 红点=未连接，用来区分
  「模块没加载/没勾作用域」和「加载了但还没匹配到」
- 「已屏蔽关键词」：以 Chip 流式排布展示全部关键词并计数
- 「测试模式」开关：给时间线前几条帖子也加按钮，用于确认模块是否生效
- 底部说明卡片

设置页与 X 进程通过广播互通（本机 ROM 把 `shared_prefs` 重定向出应用数据目录，
`XSharedPreferences` 失效，所以没有用它）。

## AI 判定（deepseek-flash）

规则匹配负责"快"，模型负责"准"。流程：

```
时间线响应
   └─ 规则筛出 "@某人," 形状的回复（只是一个候选条件）
        └─ 命中过缓存？── 是 ──> 直接用缓存结论（垃圾→加按钮 / 正常→跳过）
                        └─ 否 ──> 后台线程调用 deepseek-flash，结论写入缓存
                                  同时本次仍按本地词库决定（严格模式下则先不加按钮）
```

- **缓存**：按正文 MD5 存于 X 数据目录 `xsb_ai_cache.json`，同一条只问一次，滚动来回不重复计费。
- **异步**：调用跑在 2 线程池上（队列 64），**不阻塞**产出时间线的网络线程。
- **严格模式**：只信模型结论。没缓存的帖子本次不显示按钮，等结论回来后的下一次渲染才出现。
- **提示词**：要求只输出 JSON —— `{"spam":bool,"handle":"...","confidence":0..1,"reason":"..."}`，
  `temperature=0`、`response_format=json_object`。

**仓库与 APK 里不含任何密钥。** 安装后在模块设置页填入自己的
[DeepSeek API Key](https://platform.deepseek.com/api_keys) 即可启用；未填时 AI 判定保持关闭，
模块退回纯本地规则。

设置页可配置 **API Key / 模型 / 接口地址**，默认官方 `https://api.deepseek.com` +
`deepseek-flash`，并提供「保存并测试」按钮：拿一条样例实时调用并把结论回显在按钮下方。

> [!WARNING]
> 开启 AI 判定后，被判定为候选的**回复正文会发送到 DeepSeek 接口**。不想外发就在设置里关掉
> AI 判定，模块会退回纯本地规则。

## 实现要点

现代 X 客户端已经完全 Compose 化并且类名被 R8 混淆（本版本 APK 中
`androidx.compose.*`、`com.x.*` 的类名全部是单字母），因此模块不碰 UI 层，走**数据层**：

1. **数据入口** — 混淆不会动 OkHttp（库自带 consumer ProGuard 规则）。钩住
   `okhttp3.internal.connection.RealCall#getResponseWithInterceptorChain$okhttp`，
   只处理 `/graphql/` 响应：读出 body → 改写 → 用
   `ResponseBody.create(MediaType, String)` 重建 `Response` 并替换返回值。
   非 GraphQL 流量（图片、视频分片）完全不动，保持流式。

2. **检测** — 从 `data.timeline_response.timeline.instructions[].entries[]** 里取出
   `tweet_results.result`，读 `details.full_text` 与 `core.user_results.result.core.screen_name`，
   用 `SpamDetector` 匹配「@某人 + 逗号 + 性暗示词库」。词库覆盖中英文约 100 个词。

3. **「屏蔽」按钮** — 在帖子文本末尾追加一个 t.co 形状的短链，并挂一个 `url_entities` 条目，
   `display_url` 为「屏蔽」。X 用 `display_url` 渲染 url 实体，于是用户看到的是蓝色「屏蔽」。
   追加前会先丢掉被 `display_text_range` 隐藏的尾部链接，否则 X 会忽略我们的实体
   （见 `docs/verification.md` 的对照实验）。

4. **点击处理** — 两条独立通路，任一条命中即可：
   - **本地 Web 服务**（主）：X 进程内起一个只绑定 `127.0.0.1` 的 HTTP 服务，
     实体指向 `http://127.0.0.1:<port>/mute?h=<handle>`。这是**真实可达的链接**，
     X 会像打开普通链接一样打开它，服务收到请求就记账并返回一个确认页。
   - **Intent 拦截**（兜底/无感）：同时 hook `Activity.startActivity`、
     `ContextImpl.startActivity`、`ContextWrapper.startActivity`，
     命中标记 URL 时直接执行屏蔽并吞掉 Intent，不弹出浏览器。

5. **生效** — 关键词存于 X 数据目录的 `xsb_muted_keywords.json`。之后的每次时间线响应里，
   凡正文含该关键词或作者名匹配的条目都会从 `entries` 中移除。

## 安装

1. 从 Releases 下载 `xspamblock-<version>.apk`，或 `adb install -r` 自己构建的包。
2. 在 LSPosed 中启用「X 垃圾帖屏蔽」，作用域勾选 **X**。
3. 强行停止 X，再重新打开。
4. 打开一次模块 App：确认「运行状态」显示**已连接**，需要 AI 判定的话在「AI 判定」里填入自己的 Key。

## 已知限制

- **服务端同步**：模块会尝试把关键词回放到 X 的
  `POST /1.1/mutes/keywords/create.json`，但 X 的边缘会返回
  `400 Bad Request (cloudflare)`。原因是该请求需要 X 为**这一次具体请求**生成的设备证明
  （`X-Attest-Token` / `X-Attest-Signature`）与 transaction id，克隆一份请求无法通过，
  剥离后重放同样被拒。因此屏蔽由模块本地执行；代码里的重放保留着并在日志里报告结果，
  一旦 X 放宽校验就会自动开始同步。
- **自动翻译**：若帖子被 X 自动翻译，译文是翻译服务重新生成的字符串，注入的链接会随之丢失，
  此时「屏蔽」只显示为普通文字。点「显示原文」即可看到可点击的按钮。中文垃圾回复配中文界面
  一般不触发翻译。
- **版本相关性**：`display_url`、`display_text_range`、entity 的渲染行为是按
  X 12.25.0-prod.01 实测得到的；X 改版后需要重新校准（`docs/verification.md` 记录了校准方法）。
- **本地服务随进程冻结**：本地 Web 服务跑在 X 的进程里，X 退到后台被系统冻结时它也会暂停。
  正常使用中链接是在 X 前台时点的，不受影响；若用 `curl` 从外部探测，需要先把 X 切到前台。
- **设置页的「已连接」**：需要先启动过一次模块 App，让它脱离 stopped 状态才能收到广播。

## 构建

不依赖 Gradle/AGP，直接用手工管线（`build.ps1`）：

```
aapt2 compile --dir res -o build/res
aapt2 link ... -A assets --java build/gen
javac -source 8 -target 8 -bootclasspath <android.jar> -cp build/stubclasses ...
d8 --release --min-api 28 --lib <android.jar> ...
jar uf base.apk classes.dex && zipalign -f -p 4 && apksigner sign
```

需要 JDK 17 与 Android SDK build-tools。`stubs/` 里是 Xposed API 的编译期存根，
**只用于编译**：一旦 `de.robv.android.xposed.*` 被 dex 进模块，LSPosed 会拒绝加载
（`The Xposed API classes are compiled into the module's APK`）。`build.ps1` 里有一个
guard 专门挡住这个问题。

单元测试（检测逻辑，纯 JVM）：

```
javac -d build/test src/com/dsh/xspamblock/SpamDetector.java test/SpamDetectorTest.java
java -cp build/test com.dsh.xspamblock.SpamDetectorTest
```
