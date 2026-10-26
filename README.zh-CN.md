[English](README.md) | 简体中文

# Locsim

Locsim 是一个只做「定点」的 Android 虚拟定位应用。交互参考了 [GoGoGo（影梭）](https://github.com/ZCShou/GoGoGo) 的实现思路，但产品形态不同：

- **启动页就是保存的地点列表**，打开应用先看到自己去过的位置。
- **只有点击“添加地点”才进入地图页**，没有线路、摇杆、收藏夹和历史记录等重功能。
- UI 使用 **原生 Jetpack Compose + Material 3**。
- 地图使用 **osmdroid + OpenStreetMap**，不需要申请地图 Key；渲染层通过 `AndroidView` 嵌入 Compose。
- 通过 Android 的 `LocationManager` 测试 Provider 写入 WGS-84 坐标，无需 Root。

> 仅用于学习、开发调试和位置测试。模拟定位可能被目标应用的风控策略识别；请遵守目标应用的服务条款和当地法律法规。

## 功能

### 地点列表（首页）

- 首次启动为空状态，引导去地图选点。
- 展示名称、地址、WGS-84 经纬度；点击卡片或播放按钮开始模拟。
- 再次点击正在模拟的卡片可停止。
- 菜单支持：在地图上编辑、重命名、删除。
- 删除正在模拟的地点时会自动停止服务。

### 地图选点

- 默认从上次浏览的位置打开；编辑已有地点时定位到该地点。
- 点击地图放图钉，也可以长按图钉拖动微调。
- 顶部搜索框支持地点搜索和“纬度 经度”直接输入。
- “坐标”按钮可手动输入 WGS-84 十进制度坐标。
- 右侧提供缩放和“回到当前位置”按钮。
- 保存前可修改地点名称，地址会自动逆地理编码。
- 地图底部保留 OpenStreetMap 署名。

### 设置与离线地图

- 首页右上角进入“设置”。
- **离线地图包**：选择最近地图位置或已保存地点作为中心，按 2/5/10 km 半径和 z14–z17 下载 OSM 瓦片，保存为应用内部的 osmdroid SQLite 离线包。
- **缓存管理**：查看在线瓦片缓存大小并一键清除；列出已下载离线包，可单独删除。
- 下载完成后，下次打开地图会自动读取离线包，断网时也能浏览对应区域。
- 设置页同时提供定位权限、系统定位开关、模拟位置应用和版本信息。

### 模拟定位

- 前台服务 + 常驻通知，支持在应用退到后台后继续运行。
- 同时模拟 GPS 与 NETWORK 两个 Provider，减少部分应用仍读取网络定位导致“闪回”的情况。
- 每秒刷新一次位置，附带精度、海拔、卫星数等基础字段。
- 权限/开关未完成时，首页会显示设置清单；设置完成后自动继续刚才的模拟。
- 停止按钮、通知栏“停止”以及删除地点都会立即清理测试 Provider。

## 首次使用

1. 安装并打开 Locsim，授予定位权限。
2. 打开系统「设置 → 关于手机」，连续点击“版本号”开启开发者选项。
3. 进入「设置 → 开发者选项 → 选择模拟位置信息应用」，选择 **Locsim**。
4. 返回应用，点击“添加地点”，在地图上选点并保存。
5. 回到列表，点击地点开始模拟。

不同厂商 ROM 的菜单名称可能略有差异（例如“选择模拟位置信息应用”“模拟位置信息应用”）。

## 技术栈

| 层 | 实现 |
| --- | --- |
| UI | Jetpack Compose、Material 3、动态取色（Android 12+） |
| 状态 | ViewModel + StateFlow + `collectAsStateWithLifecycle` |
| 持久化 | SharedPreferences + JSON（地点列表、上次地图中心） |
| 地图 | osmdroid 6.1.20，OpenStreetMap France HOT 镜像 |
| 地理编码 | Android `Geocoder`（系统服务，失败时仍可使用坐标输入） |
| 定位模拟 | `LocationManager.addTestProvider` / `setTestProviderLocation` |
| 后台运行 | 前台服务，`foregroundServiceType="location"` |

## 项目结构

```text
app/src/main/java/org/iicrom/locsim/
├── LocsimApp.kt                    # osmdroid 全局配置
├── MainActivity.kt                 # Compose 入口
├── data/
│   ├── SavedPlace.kt               # 地点模型
│   ├── PlaceRepository.kt          # JSON 持久化 + StateFlow
│   ├── MapPreferences.kt           # 上次地图中心
│   └── MapCacheRepository.kt       # 在线缓存统计、离线包管理
├── location/
│   ├── MockLocationService.kt      # 前台模拟服务（GPS + NETWORK）
│   ├── MockLocationController.kt   # 启停入口
│   ├── MockLocationState.kt        # 运行状态 / 错误
│   └── MockLocationSettings.kt     # 权限、开发者选项、AppOps 检查
├── ui/
│   ├── AppRoot.kt                  # 列表 ⇄ 地图 ⇄ 设置轻量导航
│   ├── home/HomeScreen.kt          # 保存地点列表
│   ├── home/HomeViewModel.kt
│   ├── map/MapPickerScreen.kt      # osmdroid 地图选点
│   ├── map/MapPickerViewModel.kt   # 搜索、逆地理编码、保存
│   ├── map/OsmTileSource.kt        # OSM France HOT 瓦片源
│   ├── settings/SettingsScreen.kt  # 设置、离线包、缓存管理
│   ├── settings/SettingsViewModel.kt
│   └── theme/                      # Material 3 主题
└── util/Geo.kt                     # 坐标校验/格式化/解析
```

## 构建

环境：**JDK 21**、Android SDK 37、Gradle 9.6。项目通过 `gradle/gradle-daemon-jvm.properties` 将 Gradle Daemon 固定到 JDK 21；JDK 25/26 在生成 Android JDK 镜像（`jlink`）时可能失败。

运行要求：Android 12（API 31）及以上。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

项目不需要地图 API Key。默认使用 OpenStreetMap France 的 HOT 镜像（`a/b/c.tile.openstreetmap.fr/hot/`），因为部分网络无法访问 `tile.openstreetmap.org`。OSM 瓦片服务有使用政策，离线下载限制为单次最多 3000 张瓦片；正式分发前建议自建瓦片代理或改用商业地图服务，并按需替换 `OsmTileSource`。

## 与 GoGoGo 的主要差异

| | GoGoGo / 影梭 | Locsim |
| --- | --- | --- |
| 首页 | 地图 + 功能入口 | 保存地点列表 |
| 进入地图 | 启动即地图 | 点击“添加地点”后才进入 |
| 运动方式 | 定点、线路、摇杆 | 仅定点 |
| 线路/GPX | 支持 | 不支持 |
| 地图 | 百度地图（需 Key） | osmdroid + OSM（无需 Key） |
| UI | Java + XML | Kotlin + Compose |
| 数据 | SQLite | SharedPreferences + JSON |

## 已知限制

- 坐标体系固定为 **WGS-84**。国内部分地图/应用使用 GCJ-02、BD-09，显示或匹配时可能有偏移；Locsim 不对目标应用做坐标转换。
- 目标应用是否采用系统模拟位置取决于其自身实现；部分应用会通过 Wi-Fi、基站、传感器等方式辅助判断。
- 系统地理编码依赖设备厂商的 Geocoder 实现，部分设备在无 Google 服务或离线时不可用；此时仍可手动输入坐标。
- OpenStreetMap 及镜像瓦片服务不保证可用性，请遵守其 [使用政策](https://operations.osmfoundation.org/policies/tiles/)。
- 离线地图包保存在应用内部存储（`filesDir/osmdroid`），卸载应用会一并删除；清除在线缓存不会删除离线包。
- 离线下载需要保持应用在前台；单次最多 3000 张瓦片，默认 5 km + 最高 z16 约 700 张瓦片。
- 离线包与当前瓦片源名称（`OSM-HOT`）绑定；如果更换瓦片源，需要重新下载离线包。
- 未实现开机自启、线路移动、悬浮摇杆、导入导出、多开实例等功能。

## 地图署名

地图数据 © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)，以 ODbL 许可提供。
