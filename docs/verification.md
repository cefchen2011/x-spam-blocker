# 实机验证记录

设备：小米 25053RT47C（onyx），Android 16 / SDK 36 / arm64-v8a，Magisk 30.7 + LSPosed v2.1.1。
目标：`com.twitter.android` 12.25.0-prod.01（versionCode 312250001）。
所有结论都来自真机 logcat / 截图 / 无障碍树，不是推断。

## 1. Hook 点是否可达

`okhttp3` 的类名在 X 的 APK 里**完整保留**（库自带 consumer ProGuard 规则），而
`androidx.compose.*` 与 `com.x.*` 全部被混淆成单字母。因此数据层是唯一稳定的入口：

```
XSBlock: hook installed on RealCall#getResponseWithInterceptorChain
XSBlock: OP HomeTimeline len=247989
```

> 起初用 `Response.peekBody(4MB)` 读响应体，所有 GraphQL 请求都得到
> `body read failed`。改成 `body().string()` 读全文再用
> `ResponseBody.create(MediaType, String)` + `Response.newBuilder().body(...)` 重建后，
> 时间线照常渲染，说明这条路径无损。

## 2. 推文 JSON 结构（实测）

```
data.timeline_response.timeline.instructions[i].entries[j]
  .content.content.tweet_results.result
     ├─ details.full_text            正文
     ├─ details.display_text_range   实际显示区间，尾部媒体/链接 URL 常被排除在外
     ├─ core.user_results.result.core.screen_name   作者
     ├─ url_entities / mention_entities             链接与提及
     └─ quotedPostResults / legacy.repostedStatusResults / tweet.details  转推与引用
```

## 3. 「屏蔽」按钮怎么渲染才可点击（关键对照实验）

把不同的追加形态放到同一条帖子后面，一次截图看结果：

| 形态 | 渲染结果 | 可点击 |
| --- | --- | --- |
| 纯追加文本 + 扩大 `display_text_range` | `【A纯文本】` 原样显示 | — |
| `url_entities` + `display_url="屏蔽"`，切片为普通 CJK | 显示 `屏蔽` | 需实体被采纳 |
| `display_url` 含 emoji 或空格（`🚫 屏蔽`） | 原样显示，**实体被丢弃** | ❌ |
| 正文里带 t.co 链接时挂实体 | 原样显示，**实体被丢弃** | ❌ |
| 先丢掉被 `display_text_range` 隐藏的尾部 URL，再挂实体 | 显示 `屏蔽` | ✅ |
| 追加 t.co 形状短链 + 实体映射 | 显示 `屏蔽`，蓝色链接样式 | ✅ |

结论（已固化进 `Transformer.addButton`）：

1. **先剥掉隐藏的尾部 URL**，否则 X 会忽略我们挂的 url 实体；
2. 追加一个 **t.co 形状**的短链作为实体切片；
3. `display_url` 只用纯 CJK（`屏蔽`），**不要放 emoji 或空格**。

点击实测（`url` 指向真实 https 域）触发 X 的正常外链行为：

```
ActivityTaskManager: START u0 {act=android.intent.action.VIEW
  cat=[android.intent.category.BROWSABLE] dat=https://... }
  from uid 10294 callingPackage com.twitter.android
```

## 4. 点击处理与屏蔽生效

```
XSBlock: 屏蔽 tapped -> @sl_caho
XSBlock: blocked keyword added -> @sl_caho (total 1)
XSBlock: X muted-keyword create @sl_caho -> HTTP 400 (kept locally)
```

* 当前焦点仍是 `com.twitter.android`，浏览器没有被拉起 —— Intent 被吞掉了。
* 关键词落入 `/data/data/com.twitter.android/files/xsb_muted_keywords.json`。
* 下一次时间线响应：

```
XSBlock: loaded 1 blocked keyword(s)
XSBlock: hid 1 post(s) from blocked authors
```

被屏蔽作者的帖子从 `entries` 中移除，其余时间线渲染正常。

## 5. 本地 Web 服务（真实链接通路）

X 进程内绑定环回地址：

```
LISTEN  [::ffff:127.0.0.1]:33065  users:(("twitter.android",pid=3032,fd=120))
```

在设备上直接请求：

```
$ curl -si 'http://127.0.0.1:33065/mute?h=servertest'
HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
...
<p>已屏蔽</p><p>@servertest 已加入屏蔽关键词</p>
```

模块侧同步记账：

```
XSBlock: 屏蔽 link visited for @servertest
XSBlock: blocked keyword added -> @servertest (total 2)
```

两条通路都能独立完成屏蔽：链接被打开时由服务处理，未被打开时由 Intent 拦截处理。

> 注意：本地服务跑在 X 进程内，X 退到后台被系统冻结时服务也会暂停（表现为 curl 连接超时，
> 但端口仍在 LISTEN）。把 X 切到前台后立即恢复：

```
XSBlock: 屏蔽 link visited for @xiaomei520
XSBlock: blocked keyword added -> @xiaomei520 (total 1)
```

## 6. 检测逻辑单元测试

```
$ java -cp build/test com.dsh.xspamblock.SpamDetectorTest
ok   -> @xiaomei520   @xiaomei520,哥哥约吗，妹妹上门服务哦
ok   -> @abc123       @abc123,全套服务，加微信
ok   -> @nina_88      @nina_88，骚货在线等你，私聊
ok   -> @sweet        @sweet,想被哥哥疼，私聊我
ok   -> @hottie       @hottie, nude pics available, dm me
ok   -> @user123      @user123，约炮请私信
ok   -> null          @user,今天天气不错
ok   -> null          @a,吃饭了吗
ok   -> null          普通帖子，没有提及符
ok   -> null          @friend，谢谢你的帮助
ok   -> null          (空)
ok   -> null          (null)
ALL PASS
```

## 7. AI 判定（deepseek-flash）

**宿主机提示词验证**（官方接口 `https://api.deepseek.com`，模型 `deepseek-flash`，`temperature=0`）：

```
OK   [0.98s] @xiaomei520,哥哥约吗，妹妹上门服务哦      -> spam=True  handle=xiaomei520 conf=0.98
OK   [1.67s] @abc123,全套服务，加微信私聊              -> spam=True  handle=abc123     conf=0.97
OK   [1.11s] @tom, 这个项目的文档我写完了，你有空 review -> spam=False handle=tom        conf=0.99
3/3 correct   （另一轮 8 条样本同样 8/8）
```

**设备侧**：模块设置页「保存并测试」实时调用并回显：

```
测试样本：@xiaomei520,哥哥约吗，妹妹上门服务哦
判定：垃圾引流，用户名 @xiaomei520，置信度 0.99
```

**Hook 侧**：配置经广播桥接下发到 X 进程，并已在真实时间线上完成调用与缓存回落：

```
XSBlock: AI config enabled=true strict=false model=deepseek-flash
$ cat /data/data/com.twitter.android/files/xsb_ai_cache.json
{"f7d3e5fbebb46289fdadd2d482380464":{"s":false,"h":"100Mbps","c":0.98}}
```

即：时间线里一条含 `@100Mbps,` 的回复被规则选为候选 → 后台调用模型 → 结论
"正常" 落缓存 → 本次不加按钮。命中缓存后不再重复调用。

> 未能观察到的：真实垃圾回复命中后加按钮的完整链路。仪表时间线里没有可用的此类样本
> （用户实际遇到的垃圾回复多出现在评论区）。该路径复用同一份已验证的 `classify()` 与
> 同一份缓存读取逻辑。

## 8. 两级判定与关键词管理

判定逻辑已改为：带 `@` → 本地规则命中就显示；不命中再问 AI，AI 判为垃圾才显示。
候选范围从 `@某人,` 放宽到任意带 `@` 的回复（提示词也相应放宽，不再要求以 @ 开头）。
原来那个"严格模式"开关随之取消——新逻辑里 AI 分支本来就只在判为垃圾时才显示。

关键词管理实测（设置页 → X 进程 → 回传）：

```
before: ["xiaomei520","nina_88","sweet_dm"]
tap chip: @xiaomei520  ✕      -> 确认框 -> 删除
after remove one: ["nina_88","sweet_dm"]
XSBlock: blocked keyword removed -> @xiaomei520 (total 2)

tap 清空全部                   -> 确认框 -> 清空
after clear all: []
XSBlock: blocked keywords cleared (2 removed)
```

## 9. 两个把按钮「吃掉」的坑（v1.1.1 修复）

### 9.1 时间线不止一种结构

转换器原先只认 `data.timeline_response.timeline.instructions`，那是首页的结构。
搜索页走的是 `search_by_raw_query`，于是**搜索结果整页被跳过**，一屏按钮都没有。
现在改为递归遍历整份响应里所有 `instructions` 数组，并递归查找 `tweet_results`：

```
SearchTimelineQuery timelines=1 tweets=16 flagged=3
ConversationTimeline timelines=1 tweets=2  flagged=0
```

### 9.2 实体下标 X 是按码点算的

注入后有时渲染成蓝色「屏蔽」，有时却把裸 URL 打印出来。对比落盘的 JSON 才看出规律：

| 帖子 | `display_text_range` 终点 | 字符串长度(码点) | 结果 |
| --- | --- | --- | --- |
| `Codex的新版UI已经疯了` | 与长度一致 | 一致 | ✅ 渲染成「屏蔽」 |
| `比我好看的没我骚😜…@xunyuan49` | 54 | 52 | ❌ 打印裸 URL |
| `我果然太涩了🌊🤽…` | 90 | 84 | ❌ 打印裸 URL |

差值正好等于帖子里的 emoji 个数 —— **X 用 Unicode 码点算下标，而 Java 字符串是 UTF-16**
（一个 emoji 占两个单元）。带 emoji 的帖子下标整体偏移，X 认为实体越界就丢掉它。
修法是写入前转换：

```java
int start = updated.codePointCount(0, visible.length() + 2);
int end   = updated.codePointCount(0, updated.length());
```

### 9.3 顺带确认：expanded_url 必须是 https

把 `expanded_url` 指向本地 Web 服务（`http://127.0.0.1:PORT/...`）时，X 同样会丢弃整个实体、
打印裸 URL。所以控制项统一用 https 标记地址，点击由 LinkHook 吞掉 Intent 完成；
本地服务保留为兜底通路（见第 5 节）。

## 10. 踩过的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| LSPosed 报 `Cannot load module` | Xposed API 存根被编译进了模块 dex | 存根只作编译期 classpath，构建脚本加 guard |
| 所有 GraphQL 响应体读不到 | `peekBody` 路径不可用 | 改为读取全文 + 重建 Response |
| 修改结果被丢弃 | 计数变量漏加，`changed==0` 提前返回 | 修正计数 |
| 模块注册了但没生效 | 手工往 `modules_config.db` 插了 `enabled/scope` 后需重启 lspd | 重启设备 |
| 接口返回 `400 cloudflare` | 克隆的 GraphQL 请求带 `Content-Encoding: gzip`，与明文 body 不符 | 剥离 body 相关请求头（仍被设备证明拦下，见下） |
| 模块设置了开关但 Hook 侧读不到 | 本机 ROM 把 `shared_prefs` 重定向出应用数据目录，`XSharedPreferences` 路径不存在 | 改用广播桥接 |
| 官方 API 一直 401 | 从 YAML 抓 Key 的正则把行尾逗号也吃进去了（拿到的 Key 多一位） | 用 `sk-[^,\s]+` 精确匹配 |
| `deepseek-flash` 返回 content 为空 | 该模型是推理模型，`max_tokens` 太小会全被 reasoning 占满 | `max_tokens` 提到 800 |
| 设置页启动即崩 | 布局里状态圆点是 `View`，代码却强转 `TextView` | 改为 `View` |

## 11. 未能解决：X 服务端「已静音的字词」同步

尝试把关键词回放到 `POST https://api.x.com/1.1/mutes/keywords/create.json`，
用 X 自己的 `OkHttpClient`（这样请求会经过 X 的拦截器链）：

- 表单体、JSON 体都试过 → 均为 `400 Bad Request`；
- 剥离 `Content-Encoding` / `Content-Length` / `Content-Type` 后仍然 400；
- 剥离 `X-Attest-Token` / `X-Attest-Signature` 期待拦截器重新签名 → 仍然 400。

发出去的请求头与 X 自己的请求一致（Authorization、Trusted-Device-ID、
X-Twitter-* 齐全），因此判断是 X 边缘按**请求绑定**的设备证明/transaction id 校验，
克隆请求无法通过。屏蔽因此由模块在本地执行；重放代码保留并记录结果，
X 放宽校验后会自动开始同步。
