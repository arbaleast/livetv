# TV-INTERACTION — 遥控器交互与焦点规范

> 本文件既是**规范**（后续开发必须遵守），也标注了**当前实现现状**。
> 标注「未实现」的内容属于待办，**不得**在文档其他位置写成已有能力。

## 1. 按键语义总表（Android TV 约定）

| 按键 | KeyEvent | 语义 | 当前实现现状 |
|---|---|---|---|
| 上 / 下 | `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` | 在行（Row）之间纵向移动焦点 | 浏览页：依赖 Leanback 默认行为（`ListRow` 纵向排列）。**播放页**：正常态切上/下一台；侧边选台栏打开时在频道列表中上下移动焦点（详见 4.1） |
| 左 / 右 | `KEYCODE_DPAD_LEFT` / `KEYCODE_DPAD_RIGHT` | 在行内卡片之间横向移动焦点；在首列向左且有 Header 时切到 Header 栏 | 浏览页：Leanback 默认行为。**播放页**：正常态按左键呼出侧边选台栏；选台栏打开时按右键收起 |
| OK | `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` | 激活当前焦点项（= 点击） | 浏览页：触发 `MainBrowseFragment.onItemViewClickedListener`。**播放页**：Error 态手动重置；选台栏打开时选中频道并收起；正常态呼出控制器 |
| Back | `KEYCODE_BACK` | 逐层返回：先关 Header / 抽屉 / 控制器，再退到上一个 Activity | 浏览页启用 `isHeadersTransitionOnBackEnabled = true`；**播放页**：依次收起侧边选台栏 → 控制器 → 退出播放页 |
| Menu | `KEYCODE_MENU` | 打开快速选台 / 菜单 | **播放页**：已实现，呼出侧边频道选台栏（与 `KEYCODE_DPAD_LEFT` 一致）；浏览页保留独立「设置」行 |

补充约定：

- **不允许**用触摸手势作为唯一交互路径；所有操作必须能用 D-Pad + OK 完成。
- 焦点项必须有**可见的焦点态**（缩放、描边或高亮），当前由 `ImageCardView` 默认焦点态提供。
- 播放页（`PlayerActivity`）进入后应把焦点交给播放器控件或明确的返回入口，Back 必须能退出。

## 2. 焦点记忆原则

1. **返回后回到原位置**：从浏览页进入播放页再 Back 回来，焦点应落在**进入前的那张卡片**上，
   而不是列表首项。Leanback 依赖 `Row`/Fragment 的视图状态保存来实现这一点，
   因此**不要在 `onItemViewClickedListener` 里重建 adapter 或刷新列表**（重建会丢失焦点位置）。
2. **刷新列表要保留焦点**：刷新前记录当前行的**标识**（分类名 / 「设置」行名）与行内条目 id，
   刷新后**优先按标识**找回对应行——分类增删时行号会整体偏移，只按 `selectedPosition` 恢复必然错位；
   行内条目按频道 id / 动作恢复，标识找不到时退回原行号（有界），最后才落回首行。
   实现见 `MainBrowseFragment.loadRows(preserveFocus = true)` + `restoreFocus`，
   焦点标识由 `onItemViewSelectedListener` 维护。
3. **焦点不得丢失**：任何异步加载完成后，若当前焦点为空或落在已移除的卡片上，
   必须在下一个焦点请求时把焦点放回第一个可见卡片（否则遥控器"没反应"）。
4. **不劫持方向键**：除"焦点为空时的兜底恢复"外，不要在 `onKeyDown` 里拦截 D-Pad。
   （**例外**：播放页阶段三起显式拦截上下键做切台，见 4.1——这是有意为之，
   因为播放页没有可聚焦的频道列表，上下键在播放语境下的自然语义就是"换台"。）

## 2.1 播放页的焦点例外说明

浏览页的"不劫持方向键"规则**不适用于播放页**：

- 浏览页有可聚焦的卡片列表，方向键必须留给焦点系统；
- 播放页没有频道列表，方向键若不被接管就毫无作用；按 TV 设备惯例
  （如运营商 IPTV 机顶盒），上下键在播放态就是"上/下一频道"；
- 播放页的控制器（`PlayerView` 控制器）显示时，方向键交还 `PlayerView` 处理自身焦点，
  避免与控制器内的控件冲突。

## 3. Leanback Presenter 绑定 / 解绑规范

`ChannelCardPresenter` 继承 `androidx.leanback.widget.Presenter`，生命周期方法职责：

| 方法 | 必须做 | 禁止做 |
|---|---|---|
| `onCreateViewHolder(parent)` | 创建并配置 View；**只从 `parent.context` 取 Context** | 持有 Activity/Fragment 引用；在此处做 IO 或耗时计算 |
| `onBindViewHolder(holder, item)` | 取出强类型 item（当前用 `item as Channel`）；设置文本/图片等轻量属性 | 发起网络请求、读磁盘、注册长期回调 |
| `onUnbindViewHolder(holder)` | **清理**图片（`mainImage`）、徽标（`badgeImage`）等引用，避免图片加载器持有已销毁的 View | 什么都不做（会泄漏 ImageView 持有的 Drawable/Bitmap） |

补充硬性要求：

- **Presenter 不得持有 Context 之外的对象**：`ChannelCardPresenter` 当前只在 `onCreateViewHolder` 里用
  `parent.context` 创建 `ImageCardView`，不缓存 Context——这是正确范式，改动时不要破坏。
- **ViewHolder 与 Presenter 一一对应**：`ArrayObjectAdapter` 复用 `onCreateViewHolder` 产出的 ViewHolder，
  因此 `onBindViewHolder` 必须**完整覆盖**所有可变属性（当前覆盖了 `titleText`、`contentText`）。
- **图片资源要复位**：`ImageCardView` 默认会缓存图片；解绑时置 `null`（当前 `onUnbindViewHolder` 已做）。
- **Presenter 实例粒度**：当前每个 `ListRow` 各 `new` 一个 `ChannelCardPresenter`（`MainBrowseFragment.loadRows()`），
  两个实例互不共享状态；同一 Presenter 若被多行复用，其内部状态必须是无行相关的。
- **解绑回调不能缺失**：被移除的行必须触发 `onUnbindViewHolder`，否则图片引用泄漏。

## 4. 播放页交互规范

### 4.1 播放页按键映射（阶段 5.5 真机校准）

> **实现位置是 `PlayerActivity.dispatchKeyEvent`，不是 `onKeyDown`。**
> Media3 的 `PlayerView.dispatchKeyEvent` 会先于 `Activity.onKeyDown` 拿到事件，
> 并在"收到方向键且控制器未完全可见"时调用 `maybeShowController()` 后**吞掉事件**。
> 只在 `onKeyDown` 里处理会导致真机上切台与 OK 重试完全失效（阶段 5.5 实测发现 D1/D2）。
> 因此按键拦截前移到 `dispatchKeyEvent`，在视图树分发之前完成。

| 按键 | KeyEvent | 控制器**未由用户呼出**时 | 控制器**已由用户呼出**时 |
|---|---|---|---|
| 上 | `KEYCODE_DPAD_UP` | **切上一台**（循环：首 → 末），显示"已切换到：xxx" | 交还 `PlayerView` 或侧边选台栏，焦点在列表/控制器内移动 |
| 下 | `KEYCODE_DPAD_DOWN` | **切下一台**（循环：末 → 首），显示"已切换到：xxx" | 同上 |
| 左 / 菜单 | `KEYCODE_DPAD_LEFT` / `KEYCODE_MENU` | **呼出侧边频道选台栏**并自动聚焦当前频道 | 交还控制器或选台栏 |
| 右 | `KEYCODE_DPAD_RIGHT` | 交回系统默认处理 | 选台栏打开时**收起选台栏**；控制器打开时交还控制器 |
| 确认 | `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` | **Error 态**：手动重置（回主源、计数归零、立即重连）；正常态：呼出控制器并记为"用户呼出" | 选台栏打开时直接切到焦点频道并收起；控制器打开时执行按钮操作 |
| 返回 | `KEYCODE_BACK` | 关闭 Activity（退出播放页） | 优先**收起侧边选台栏**，其次**隐藏控制器**并清除"用户呼出"标记 |

除上表列出的绑定外，其余按键一律交回系统默认处理：TV 正常播放态的 `KEYCODE_DPAD_RIGHT` 不拦截；
`KEYCODE_DPAD_LEFT`/`KEYCODE_MENU` 只用于呼出侧边选台栏，选台栏打开时 `KEYCODE_DPAD_RIGHT` 用于收起
（实现见 `util/PlayerKeyPolicy.kt`）。

**控制器可见性的判定规则（重要）**：

- 不查 `PlayerView.isControllerFullyVisible`：它在隐藏动画期间仍返回 `true`。
- 也不以"控制器可见"作为切台闸门：Media3 在切换媒体源后会**异步**弹起控制器，
  而控制器一显示就会吞掉方向键，导致连续换台从第二下起失效（实测 D4）。
- 实际规则：`controllerAutoShow = false`（控制器不再自动弹出），
  切台只看"用户是否主动呼出过控制器"这个标记；Media3 自行弹出不影响切台。

### 4.2 切台的连带行为

- 切台**必须**丢弃旧频道的重试状态（`RetryPolicy.onChannelSwitched`）：新频道从主源、计数 0 重新开始。
- 切台**不重建 ExoPlayer**：`PlayerManager` 在 `onStart` → `onStop` 期间复用同一个实例，
  只重建 `MediaSource`（请求头随频道变化）。
- 切台**不新建 Channel**：新频道来自 `ChannelNavigator`（仓库扁平列表），定位失败时保留当前频道不变。
- 切台会清空待执行的重试任务（`retryHandler.removeCallbacksAndMessages(null)`），
  且旧任务的延迟回调会校验"频道没变"，避免误伤新频道。

### 4.3 播放页规范与现状

| 项 | 规范 | 现状 |
|---|---|---|
| 进入 | OK 触发后由 `MainBrowseFragment` `startActivity(PlayerActivity)`，通过 `EXTRA_CHANNEL_ID` / `EXTRA_CHANNEL_NAME` / `EXTRA_STREAM_URL` 传参 | 已实现（阶段三新增 `EXTRA_CHANNEL_ID`） |
| 频道定位 | 用 `EXTRA_CHANNEL_ID` 在仓库中定位完整频道（含 `backupUrls` / `headers`）；未命中则按地址匹配；再未命中用 Extra 构造临时单频道 | 已实现，保证旧调用 100% 兼容 |
| 返回 | Back 退出播放页回到浏览页，焦点按第 2 节规则恢复 | 依赖 Leanback/系统默认，**需真机验证** |
| 全屏 | 隐藏状态栏与导航栏（沉浸式） | 已实现：改用 `WindowInsetsControllerCompat` + `WindowCompat.setDecorFitsSystemWindows(window, false)`，并处理 `displayCutout` / `systemBars` 安全区 |
| 屏幕方向 | TV 端锁定横屏；触控设备播放页跟随设备方向自动旋转（不中断播放、不丢当前频道） | 已实现：`PlayerOrientationPolicy` 分端设 `requestedOrientation`（TV 锁 `LANDSCAPE`，触控 `FULL_USER`），配合 `configChanges` + `onConfigurationChanged` 无缝旋转与 `onSaveInstanceState` 频道恢复 |
| 屏幕常亮 | 播放期间禁止自动熄屏 | 已实现：`PlayerActivity.onCreate` 里 `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` |
| 加载/缓冲提示 | Loading / Buffering / 自动重试 / 切备用线时显示提示，Playing 时隐藏 | 已实现（阶段三新增 `statusText`，文案见 `strings.xml` 的 `player_*`） |
| 错误提示 | 按错误分类给出说明 + "按确认键重试 · 按上下键切换频道"指引 | 已实现（阶段三改为按 `PlaybackError` 分类，文案全部走 `strings.xml`） |
| 菜单 | Menu 键呼出播放设置（码率/线路） | **未实现** |
| 触控浮层 | 仅触控设备启用；TV 端所有触控视图 `GONE`，不参与焦点 | 已实现（阶段七）：`PlayerActivity.setupTouchUi()` |
| 触控切台 | 底部"上一台/下一台"复用 `switchChannel(direction)`，与遥控器同一条状态流 | 已实现（阶段七） |
| 触控抽屉 | 右侧 280dp 分类抽屉，当前频道标"播放中"并自动滚动定位 | 已实现（阶段七）：`openDrawer()` + `ChannelDrawerModel` |

### 4.4 触控端（手机 / 平板）播放交互

**入口隔离**：`isTouchDevice = !DeviceUtils.isTvDevice(this)`。所有触控视图（`view_tap_catcher` /
`touch_overlay_container` / `drawer_scrim` / `channel_drawer`）在布局里默认 `GONE`，
只有 `isTouchDevice` 为真时才被置为可见。因此 **TV 端的焦点树与按键路径完全不含这些视图**，
阶段 5.5 的 D-Pad 行为（上下切台、OK 重试/呼出控制器、Back 两段式）不受影响。

| 操作 | 行为 |
|---|---|
| 轻触画面 | 切换浮层显隐 |
| 浮层顶栏「返回」 | `finish()` 退出播放页 |
| 浮层顶栏「频道列表」 | 打开右侧抽屉（遮罩可点关闭） |
| 底部「上一台」/「下一台」 | `switchChannel(direction = -1 / 1)` → `switchToChannel(channel)`，与遥控器共用 |
| 底部「重试」 | 仅错误态可见，调用 `playerManager.manualReset()` |
| 抽屉条目 | `navigator.locate(id, url)` 后 `switchToChannel(channel)`，随即收起抽屉 |
| 系统 Back | 抽屉打开 → 只关抽屉；控制器打开 → 只关控制器；否则退出 |

**自动隐藏**：显示后 `TOUCH_OVERLAY_AUTO_HIDE_DELAY_MS = 5000` 自动收起；
**错误态不自动隐藏**——否则"重试"按钮会在用户点之前消失。
调度用 `binding.root.postDelayed(hideOverlayRunnable, …)`，`onDestroy()` 里
`binding.root.removeCallbacks(hideOverlayRunnable)`，不持有 Activity、不泄漏。

**状态同步**：`renderState()` 每次都会同步 `overlayRetry` 的可见性——
离开错误态必须立刻收起"重试"，否则切台后会残留一个点了没反应的按钮（阶段七真机实测发现）。

**文案分端**：触控端错误提示是 `点按「重试」重新连接 · 用下方按钮切换频道`（`player_error_hint_touch`），
TV 端仍是 `按确认键重试 · 按上下键切换频道`（`player_error_hint`）。

**自动旋转（仅播放页）**：`PlayerActivity` 在 `AndroidManifest.xml` 中不锁死方向并声明
`configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`；
进入时通过 `PlayerOrientationPolicy.resolveRequestedOrientation(isTvDevice)` 动态设置方向——
TV 端继续锁定横屏（`SCREEN_ORIENTATION_LANDSCAPE`），触控端跟随设备方向（`SCREEN_ORIENTATION_FULL_USER`，
支持竖屏与正/反横屏且遵循系统自动旋转开关）。旋转时由 `onConfigurationChanged` 刷新沉浸式隐藏栏与
`displayCutout`/`systemBars` 安全区，不释放 `ExoPlayer`、不中断播放；同时通过 `onSaveInstanceState`
保存当前频道 ID，防止后台重建回退到初始频道。

## 5. 无障碍与文案

- 所有可见文案必须走 `strings.xml`（阶段三已把播放页文案全部迁入 `strings.xml` 的 `player_*`）。
- 焦点项要有可读的内容描述；`ImageCardView` 的标题/描述即读屏文案来源。
- 颜色对比度要满足 TV 大屏远距离观看；品牌色当前为 `#1565C0`（`@color/brand_primary`）。
- 错误提示必须给出**可执行的指引**（当前为"按确认键重试 · 按上下键切换频道"），
  不要只报错误码——用户在电视上无法操作调试信息。

## 6. 新增界面时的自检清单

1. 能否只用 D-Pad + OK + Back 完成全部操作？
2. 进入/退出后焦点是否在合理位置、是否被重建列表冲掉？
3. Presenter 的 `onUnbindViewHolder` 是否清理了所有引用？
4. 是否误用了触摸专用控件（默认 Leanback 不保证）？
5. 是否新增了硬编码文案（应进 `strings.xml`）？
6. 新增触控视图是否在 XML 里默认 `GONE`，并只在 `isTouchDevice` 时显示？（否则 TV 焦点会被抢）
7. 新的延时/动画是否在 `onDestroy` 里移除了回调？
