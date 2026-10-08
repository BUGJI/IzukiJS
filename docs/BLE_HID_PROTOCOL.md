# Izuki JS · 蓝牙 HID 控制协议

版本：v2（`HidProtocol.PROTOCOL_VERSION = 2`）

本协议约定 **Izuki JS App（Android，BLE Central / HID Host）** 与
**外部 HID 数位板狗（如 ESP32，BLE Peripheral / HID Device）** 之间的控制通道。

## 1. 角色与数据流

```
脚本 click(x, y)
  └─ HidController → HidGattClient (BLE Central)
        └─ 控制特征写入 TAP 帧
              └─ 狗 (BLE Peripheral)
                    └─ 通过 HID 上报绝对坐标触摸 → 手机系统触摸事件 → 控制本机
```

- 手机为 HID **主机**（接收狗上报的输入）。
- App 为 BLE **客户端**（连接狗的自定义 GATT 服务下发指令）。
- 狗对外呈现为 **Digitizer / Touch Screen（绝对坐标）**，这是 `click(x,y)` 能原生支持的关键。
  （标准鼠标只有相对位移，无法直接定位坐标。）

## 2. 自定义 GATT 服务

| 名称 | UUID | 属性 | 方向 |
|---|---|---|---|
| Service | `7d8a0001-9a1e-4b2a-8f3c-1d2e3f4a5b6c` | Primary | — |
| Control | `7d8a0002-9a1e-4b2a-8f3c-1d2e3f4a5b6c` | Write / Write Without Response | App → 狗 |
| Event | `7d8a0003-9a1e-4b2a-8f3c-1d2e3f4a5b6c` | Notify | 狗 → App |

- 命令默认用 **Write Without Response** 以降低延迟；BLE 保证同一连接内写入顺序。
- App 连接后会订阅 Event（CCCD = `0x0001`）。
- App 连接后会协商 MTU（请求 517，实际以 `onMtuChanged` 为准）。

## 3. HID 上报描述符（狗侧）

狗对外至少两个用途：**触摸数位板**（注入坐标）与 **键盘**（注入按键/文本）。
建议使用 Report ID 区分。

触摸（绝对坐标，单指）参考描述符：

```
05 0D        Usage Page (Digitizer)
09 02        Usage (Pen)             ; 必须是 Pen，见下方说明
A1 01        Collection (Application)
  85 01        Report ID (1)
  09 22        Usage (Finger)
  A1 02        Collection (Logical)
    09 42        Usage (Tip Switch)
    15 00 25 01  Logical Min 0, Logical Max 1
    75 01 95 01  Report Size 1, Report Count 1
    81 02        Input (Data,Var,Abs)
    75 07 95 01  Report Size 7, Report Count 1
    81 03        Input (Const)          ; 补齐 1 字节
    09 30        Usage (X)
    09 31        Usage (Y)
    15 00        Logical Min 0
    26 FF 7F     Logical Max 32767
    75 10 95 02  Report Size 16, Report Count 2
    81 02        Input (Data,Var,Abs)   ; X,Y 各 16 位小端
  C0           End Collection
C0           End Collection
```

键盘（标准 Boot Keyboard，Report ID 2）参考：

```
05 01        Usage Page (Generic Desktop)
09 06        Usage (Keyboard)
A1 01        Collection (Application)
  85 02        Report ID (2)
  05 07        Usage Page (Key Codes)
  19 E0 29 E7  Usage Min 0xE0, Usage Max 0xE7   ; 修饰键
  15 00 25 01  Logical 0..1
  75 01 95 08  Report Size 1, Report Count 8
  81 02        Input (Data,Var,Abs)
  95 01 75 08  Report Size 8, Count 1
  81 03        Input (Const)                  ; 保留字节
  05 07 19 00  Usage Page Key Codes, Usage Min 0
  29 65        Usage Max 0x65
  15 00 25 65  Logical 0..101
  75 08 95 06  Report Size 8, Count 6
  81 00        Input (Data,Ary,Abs)          ; 6 个按键
C0
```

> 逻辑量程（示例 0–32767）需与第 5 节的 `SET_RESOLUTION` 映射一致。

> **为什么用 `Usage (Pen)` 而不是 `Usage (Touch Screen)`：** Linux 内核
> `hid-input.c` 只对 `Digitizer(0x01)` 置 `INPUT_PROP_POINTER`、对 `Pen(0x02)`
> 置 `INPUT_PROP_DIRECT`；单纯声明 `Touch Screen(0x04)` 的单点描述符两者都不置，
> Android InputReader 会把绝对坐标设备当成**间接触摸板**，指针停在 (0,0) 无法操控。
> 声明为 `Pen` 即被识别为直接触摸屏。（多点触摸描述符若声明 `Contact Identifier`
> 让 `hid-multitouch` 接管，也会自动获得 `INPUT_PROP_DIRECT`；本项目保持单点。）

## 4. 帧格式

所有多字节整数 **小端序（Little-Endian）**。

### 4.1 App → 狗（Control 特征）

| CMD | 值 | 载荷布局 | 说明 |
|---|---|---|---|
| HANDSHAKE | `0x01` | `ver:u8` | App 连接后首先发送 |
| SET_RESOLUTION | `0x02` | `width:u16, height:u16` | 手机屏幕像素，狗据此线性映射 |
| TAP | `0x03` | `x:u16, y:u16, duration_ms:u16` | 绝对坐标点击/长按 |
| SWIPE | `0x04` | `x1:u16,y1:u16,x2:u16,y2:u16,duration_ms:u16,steps:u8` | 滑动 |
| KEY | `0x05` | `usage:u8, modifier:u8, down:u8` | HID 键盘 usage，down=1/0 |
| TEXT | `0x06` | `len:u16, utf8[len]` | 文本（狗按字符映射按键，仅 ASCII 可靠）|
| PING | `0x07` | — | 心跳 |
| GESTURE | `0x08` | `flags:u8, count:u8, count * {x:u16,y:u16,dt:u16}` | 单指复杂轨迹（曲线/停顿），见 4.1.1 |

#### 4.1.1 GESTURE 轨迹帧（v2）

- `flags` bit0 = `KEEP_DOWN(0x01)`：本帧结束后**不抬手**，供 App 把长轨迹拆成多帧续传。
- `flags` bit1 = `START(0x02)`：本帧是一条新轨迹的首帧，固件先抬手清除上一条被打断
  手势残留的触点，避免从陈旧坐标（常见为 `0,0`）起滑。App 在每条笔画的第一个帧置位。
- 每点 `dt` 表示"从上一个点移动到本点"所花毫秒；相邻两点坐标相同即**原地按住**停顿。
- 第一条轨迹点用于按下，随后各点按 `dt` 线性插值移动；帧末（未置 `KEEP_DOWN`）抬手。
- 单帧点数上限 `(260-3)/6 = 42`；App 侧超限拆帧，帧间保持按下。
- 仅**单指**（触摸报告只有一路）。多指手势不支持，`gesture()` 会顺序执行各笔画。

```js
// 对应脚本：底部上滑 260ms，在终点按住 700ms
gesture([[
  { x: 540, y: 2200, t: 0 },    // 按下
  { x: 540, y: 1200, t: 260 },  // 260ms 滑到
  { x: 540, y: 1200, t: 960 }   // 原地按住 700ms
]]);
```

### 4.2 狗 → App（Event 特征）

| EVT | 值 | 载荷布局 | 说明 |
|---|---|---|---|
| HANDSHAKE_ACK | `0x81` | `ver:u8, width:u16, height:u16` | 狗的数位板逻辑分辨率 |
| ACK | `0x82` | `seq:u8` | 可选，对配置类命令的确认 |
| STATUS | `0x83` | `flags:u8` | 就绪状态位，见 4.3 |
| ERROR | `0x84` | `code:u8` | 错误码，见 4.4 |
| PONG | `0x87` | — | PING 回应 |

### 4.3 STATUS 状态位（`0x83` 载荷）

狗在**订阅 Event 后**、**加密状态变化后**、以及**注入失败恢复时**上报。

| 位 | 值 | 名称 | 含义 |
|---|---|---|---|
| bit0 | `0x01` | LINK_UP | 狗已与手机建立连接 |
| bit1 | `0x02` | ENCRYPTED | 链路已完成配对/加密 |
| bit2 | `0x04` | HID_READY | **链路 + 加密都满足，HID 可注入** |
| bit3 | `0x08` | APP_READY | App 已订阅 Event（控制通道可用） |

> App 只有收到 `HID_READY` 才应把 HID 视为可操作；仅连通 GATT 不代表能注入。

### 4.4 ERROR 错误码（`0x84` 载荷）

| code | 名称 | 含义 |
|---|---|---|
| `0x01` | HID_NOT_LINKED | 狗未连上 HID 主机，注入被丢弃 |
| `0x02` | HID_NOT_READY | 链路未加密，HID 注入不可用 |
| `0x03` | INPUT_SET | HID 报表注入失败 |

## 5. 坐标映射

1. App 连接就绪后发送 `SET_RESOLUTION(screenW, screenH)`。
2. `TAP/SWIPE` 中的坐标均为 **手机屏幕像素**。
3. 狗按需换算到自身数位板逻辑量程：

   ```
   rawX = round(x / screenW * (digitizerMaxX + 1))
   rawY = round(y / screenH * (digitizerMaxY + 1))
   ```

4. 触摸按下 → 上报 `Tip Switch=1 + X/Y`；抬起 → `Tip Switch=0`。

## 6. 连接流程

```
App                                  狗
 │  connectGatt (TRANSPORT_LE)
 │ ─────────────────────────────────▶ │
 │  discoverServices
 │  subscribe Event (CCCD)
 │  requestMtu(517)
  │  HANDSHAKE(ver=2)                  │
 │ ─────────────────────────────────▶ │
  │                        HANDSHAKE_ACK(ver,w,h)
  │ ◀───────────────────────────────── │
  │                        STATUS(flags) ; HID_READY 置位后才可操作
  │ ◀───────────────────────────────── │
  │  SET_RESOLUTION(screenW, screenH)  │
  │ ─────────────────────────────────▶ │
  │        (HID_READY 置位，可下发 TAP/SWIPE/KEY/TEXT)
```

> 连接成功 ≠ 可操作。App 需等 `STATUS` 的 `HID_READY` 位（链路 + 加密）置位；
> 在此之前 UI 应显示"已连接，HID 通道未就绪"，而不是"已就绪"。

断线后 App 会按 `autoConnect` 策略自动重连（默认 2s 后重试）。

## 7. 已知限制

- HID 模式无 **控件树 / 截图 / Shell**；建议搭配「无障碍读控件树 + HID 注入」混合使用。
- `TEXT` 仅对 ASCII 可靠；中文输入需目标端有可用输入法，HID 键盘无法直接输入中文。
- 复杂轨迹（`gesture()` / `CMD_GESTURE`）自 **v2 固件**起支持，且仅**单指**；
  旧固件（v1）仍可 `tap/swipe`，`gesture()` 会回退为分段 `swipe`。
- `dt` 精度受固件 `vTaskDelay` 的 tick 粒度限制（默认约 10ms），快速曲线会有轻微抖动。
- 坐标精度取决于狗的数位板量程与手机的触摸校准。

## 8. 参考实现位置（Android 侧）

- `controller/hid/HidProtocol.kt` — 帧编解码与常量
- `controller/hid/HidGattClient.kt` — 扫描/连接/GATT/订阅/重连
- `controller/hid/HidController.kt` — `DeviceController` 实现
- `controller/hid/HidManager.kt` — 生命周期与注册
