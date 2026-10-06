# Izuki JS · 蓝牙 HID 控制协议

版本：v1（`HidProtocol.PROTOCOL_VERSION = 1`）

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
09 04        Usage (Touch Screen)
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

### 4.2 狗 → App（Event 特征）

| EVT | 值 | 载荷布局 | 说明 |
|---|---|---|---|
| HANDSHAKE_ACK | `0x81` | `ver:u8, width:u16, height:u16` | 狗的数位板逻辑分辨率 |
| ACK | `0x82` | `seq:u8` | 可选，对配置类命令的确认 |
| STATUS | `0x83` | `battery:u8, mode:u8` | 状态上报 |
| ERROR | `0x84` | `code:u8` | 错误 |
| PONG | `0x87` | — | PING 回应 |

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
 │  HANDSHAKE(ver=1)                  │
 │ ─────────────────────────────────▶ │
 │                        HANDSHAKE_ACK(ver,w,h)
 │ ◀───────────────────────────────── │
 │  SET_RESOLUTION(screenW, screenH)  │
 │ ─────────────────────────────────▶ │
 │        (就绪，可下发 TAP/SWIPE/KEY/TEXT)
```

断线后 App 会按 `autoConnect` 策略自动重连（默认 2s 后重试）。

## 7. 已知限制

- HID 模式无 **控件树 / 截图 / Shell**；建议搭配「无障碍读控件树 + HID 注入」混合使用。
- `TEXT` 仅对 ASCII 可靠；中文输入需目标端有可用输入法，HID 键盘无法直接输入中文。
- 多指手势 v1 未定义；`gesture()` 返回 `false`。
- 坐标精度取决于狗的数位板量程与手机的触摸校准。

## 8. 参考实现位置（Android 侧）

- `controller/hid/HidProtocol.kt` — 帧编解码与常量
- `controller/hid/HidGattClient.kt` — 扫描/连接/GATT/订阅/重连
- `controller/hid/HidController.kt` — `DeviceController` 实现
- `controller/hid/HidManager.kt` — 生命周期与注册
