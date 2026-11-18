# TV-INTERACTION — 遥控器交互与焦点规范

> 本文件既是**规范**（后续开发必须遵守），也标注了**当前实现现状**。
> 标注「未实现」的内容属于待办，**不得**在文档其他位置写成已有能力。

## 1. 按键语义总表（Android TV 约定）

| 按键 | KeyEvent | 语义 | 当前实现现状 |
|---|---|---|---|
| 上 / 下 | `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` | 在行（Row）之间纵向移动焦点 | 依赖 Leanback 默认行为：`ListRow` 纵向排列，`BrowseSupportFragment` 自动处理 |
| 左 / 右 | `KEYCODE_DPAD_LEFT` / `KEYCODE_DPAD_RIGHT` | 在行内卡片之间横向移动焦点；在首列向左且有 Header 时切到 Header 栏 | 依赖 Leanback 默认行为（`headersState = HEADERS_ENABLED`） |
| OK | `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` | 激活当前焦点项（= 点击） | Leanback 默认把 OK 转成点击 → 触发 `MainBrowseFragment.onItemViewClickedListener` |
| Back | `KEYCODE_BACK` | 逐层返回：先关 Header，再退到上一个 Activity | 已启用 `isHeadersTransitionOnBackEnabled = true`（`MainBrowseFragment.setupUi()`） |
| Menu | `KEYCODE_MENU` | 打开应用级菜单 / 设置 | **未实现**：全项目无 `onKeyDown` 处理 Menu 键，无菜单页 |

补充约定：

- **不允许**用触摸手势作为唯一交互路径；所有操作必须能用 D-Pad + OK 完成。
- 焦点项必须有**可见的焦点态**（缩放、描边或高亮），当前由 `ImageCardView` 默认焦点态提供。
- 播放页（`PlayerActivity`）进入后应把焦点交给播放器控件或明确的返回入口，Back 必须能退出。

## 2. 焦点记忆原则

1. **返回后回到原位置**：从浏览页进入播放页再 Back 回来，焦点应落在**进入前的那张卡片**上，
   而不是列表首项。Leanback 依赖 `Row`/Fragment 的视图状态保存来实现这一点，
   因此**不要在 `onItemViewClickedListener` 里重建 adapter 或刷新列表**（重建会丢失焦点位置）。
2. **刷新列表要保留焦点**：若将来列表来自 Repository 且支持刷新，需在刷新前后记录
   `selectedPosition` 与焦点卡片 id，刷新后主动恢复（Leanback 提供 `setSelectedPosition`）。
3. **焦点不得丢失**：任何异步加载完成后，若当前焦点为空或落在已移除的卡片上，
   必须在下一个焦点请求时把焦点放回第一个可见卡片（否则遥控器"没反应"）。
4. **不劫持方向键**：除"焦点为空时的兜底恢复"外，不要在 `onKeyDown` 里拦截 D-Pad。

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

| 项 | 规范 | 现状 |
|---|---|---|
| 进入 | OK 触发后由 `MainBrowseFragment` `startActivity(PlayerActivity)`，通过 `EXTRA_CHANNEL_NAME` / `EXTRA_STREAM_URL` 传参 | 已实现 |
| 返回 | Back 退出播放页回到浏览页，焦点按第 2 节规则恢复 | 依赖 Leanback/系统默认，**需真机验证** |
| 全屏 | 隐藏状态栏与导航栏（沉浸式） | 已实现，但用的是**已废弃**的 `systemUiVisibility`（`PlayerActivity.hideSystemUi()`，已 `@Suppress("DEPRECATION")`）；API 30+ 应改用 `WindowInsetsController`（**未实现**） |
| 屏幕常亮 | 播放期间禁止自动熄屏 | 已实现：`PlayerActivity.onCreate` 里 `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` |
| 错误提示 | 播放失败时在页面上给出文案，不崩溃 | 已实现：`PlayerActivity` 的 `Player.Listener.onPlayerError` 显示 `binding.errorText`，文案硬编码为中文（**未走 `strings.xml`**） |
| 菜单 | Menu 键呼出播放设置（码率/线路） | **未实现** |

## 5. 无障碍与文案

- 所有可见文案必须走 `strings.xml`（当前 `PlayerActivity` 的"播放失败: ..."是**反例**，待修）。
- 焦点项要有可读的内容描述；`ImageCardView` 的标题/描述即读屏文案来源。
- 颜色对比度要满足 TV 大屏远距离观看；品牌色当前为 `#1565C0`（`@color/brand_primary`）。

## 6. 新增界面时的自检清单

1. 能否只用 D-Pad + OK + Back 完成全部操作？
2. 进入/退出后焦点是否在合理位置、是否被重建列表冲掉？
3. Presenter 的 `onUnbindViewHolder` 是否清理了所有引用？
4. 是否误用了触摸专用控件（默认 Leanback 不保证）？
5. 是否新增了硬编码文案（应进 `strings.xml`）？
