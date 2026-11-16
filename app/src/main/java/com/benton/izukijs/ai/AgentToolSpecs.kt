package com.benton.izukijs.ai

import org.json.JSONArray
import org.json.JSONObject

internal fun buildAgentToolSpecs(shellAvailable: Boolean): List<ToolSpec> = buildList {
    // ---- 观察 ----
    add(
        ToolSpec(
            "ocr",
            "识别当前屏幕文字，返回结构化文本块：每块含文字与中心坐标 (x,y)、宽高、置信度。坐标为屏幕像素，可直接用于 click。" +
            "注意本地 OCR 有精度边界（0/O、I/l、简繁、生僻字形易混，置信度常为 0.5~0.8），" +
                "不要用 OCR 文本做精确匹配或关键分支判断，优先用 ui_dump / find(source=ui) 的控件文本交叉验证。" +
                "lines=true 时按行聚合（同一行的多个块合并，按阅读顺序返回）。",
            objectSchema(
                mapOf(
                    "lines" to booleanProp("是否按行聚合返回 lines（默认 false，返回 blocks）"),
                    "max_gap_y" to numberProp("lines=true 时同一行的最大垂直中心差（像素）"),
                    "max_gap_x" to numberProp("lines=true 时同行拆段的最大水平间距（像素）"),
                    "roi" to objectSchema(
                        mapOf(
                            "x" to numberProp("ROI 左上角 x"),
                            "y" to numberProp("ROI 左上角 y"),
                            "w" to numberProp("ROI 宽度"),
                            "h" to numberProp("ROI 高度"),
                        ),
                    ),
                ),
            ),
        ),
    )
    add(
        ToolSpec(
            "ui_dump",
            "读取当前界面的控件树（含文本、控件 id、内容描述与屏幕坐标）。返回 JSON（tree 为缩进文本）；持续动画界面会快速降级而不是白等。当 OCR 不够精确时使用。" +
                "selector 可传选择器 DSL 或条件对象，返回 explain（逐条件命中数）与 codegen（更稳定的选择器）。",
            objectSchema(
                mapOf(
                    "selector" to stringProp("选择器 DSL 或 JSON 条件；例如 text=登录 clickable=true"),
                    "dsl" to stringProp("selector 的别名"),
                    "explain" to booleanProp("仅为 true 且 selector 非空时返回 explain"),
                ),
            ),
        ),
    )
    add(
        ToolSpec(
            "find",
            "查找屏幕元素并返回匹配项（中心坐标 + 边界）。text 先按 OCR 行聚合做模糊包含匹配（同一行的多个 OCR 块会拼接），未命中再按编辑距离容错；也可用 by=id|desc + value 精确匹配控件。" +
                "纯文字查找建议 source=auto（默认）：优先走 OCR（约 0.6~1s），未命中再回退控件树；source=ocr 最快，source=ui 只读控件树（较慢但精确），source=both 双源交叉验证。" +
                "未命中时附带 explain（逐条件命中数）；UI 命中时附带 selector_dsl / selector_js，可直接用于 selector.click。",
            objectSchema(
                mapOf(
                    "text" to stringProp("要查找的文字（模糊包含）"),
                    "by" to enumProp("按控件属性精确匹配", listOf("id", "desc")),
                    "value" to stringProp("by 对应的匹配值"),
                    "source" to enumProp("查找来源", listOf("auto", "ocr", "ui", "both")),
                    "fuzzy" to booleanProp("OCR 精确匹配失败后是否按编辑距离容错，默认 true"),
                    "min_conf" to numberProp("忽略置信度低于该值的 OCR 块（0~1，默认 0）"),
                ),
            ),
        ),
    )
    add(
        ToolSpec(
            "screenshot",
            "保存一张当前屏幕截图到文件，返回文件路径。",
            objectSchema(emptyMap()),
        ),
    )
    add(
        ToolSpec(
            "info",
            "读取设备信息。what=device：屏幕尺寸/机型/系统；battery：电量；current_app / foreground：当前前台包名；" +
                "usage：最近使用过的应用（需使用情况访问）。target 可指定 auto / main / virtual；" +
                "auto 在虚拟屏激活时同时返回虚拟屏与主屏的前台包名。",
            objectSchema(
                mapOf(
                    "what" to enumProp(
                        "要读取的信息",
                        listOf("device", "battery", "current_app", "foreground", "usage"),
                    ),
                    "target" to enumProp("前台包名的目标屏幕", listOf("auto", "main", "virtual")),
                ),
            ),
        ),
    )
    add(
        ToolSpec(
            "notifications",
            "读取通知栏或 Toast（只读）。action=list：当前通知列表，需「通知使用权」，支持 package / limit / since_ms / exclude_ongoing 过滤；" +
                "action=latest：等待 package 指定的应用在本次调用后出现的新通知（用于抓取验证码 / 消息）。" +
                "action=toast_list：最近的 Toast 列表；action=toast_latest：等待匹配的新 Toast。Toast 需要无障碍服务，不需要通知使用权。",
            objectSchema(
                mapOf(
                    "action" to enumProp(
                        "操作",
                        listOf("list", "latest", "toast_list", "toast_latest"),
                    ),
                    "package" to stringProp("按应用包名过滤；action=latest 时必填"),
                    "contains" to stringProp("toast_latest 只接受文本包含该片段的 Toast"),
                    "limit" to numberProp("action=list / toast_list 最多返回条数（默认 20，上限 200）"),
                    "since_ms" to numberProp("只返回 postTime 不早于该时间戳的通知"),
                    "exclude_ongoing" to booleanProp("忽略常驻 / 进行中的通知，默认 false"),
                    "detail" to booleanProp("action=list 是否返回完整字段（默认 false，只返回精简字段）"),
                    "timeout_ms" to numberProp("action=latest / toast_latest 的等待超时（毫秒），默认 10000"),
                ),
            ),
        ),
    )
    add(
        ToolSpec(
            "notification_action",
            "对通知执行写操作（需「通知使用权」）。action=click 触发通知的 contentIntent，可能打开敏感页面；" +
                "action=delete 优先触发应用自己的 deleteIntent，否则系统取消。需要先用 notifications(action=list, detail=true) 取得 key。",
            objectSchema(
                mapOf(
                    "action" to enumProp("操作", listOf("click", "delete")),
                    "key" to stringProp("通知 key；不是包名"),
                ),
                required = listOf("action", "key"),
            ),
        ),
    )

    add(
        ToolSpec(
            "events",
            "读取无障碍事件（需无障碍服务）。action=list：最近事件，新的在前；action=wait：等待匹配事件；" +
                "action=idle：等待界面安静；action=window：最近窗口信息；action=status：缓冲状态。" +
                "事件类型：window_state / window_content / windows_changed / view_scrolled / view_clicked / text_changed / notification / toast / key_down / key_up。",
            objectSchema(
                mapOf(
                    "action" to enumProp("操作", listOf("list", "wait", "idle", "window", "status")),
                    "type" to stringProp("单个事件类型，例如 window_state"),
                    "types" to JSONObject().apply {
                        put("type", "array")
                        put("description", "事件类型数组；与 type 二选一")
                        put("items", JSONObject().put("type", "string"))
                    },
                    "package" to stringProp("只匹配该包名的事件"),
                    "text_contains" to stringProp("只匹配 text / desc 包含该片段的事件"),
                    "since_seq" to numberProp("只读取序号大于该值的事件"),
                    "limit" to numberProp("action=list 最多返回条数（默认 50，上限 200）"),
                    "timeout_ms" to numberProp("action=wait / idle 的超时（毫秒），默认 10000"),
                    "quiet_ms" to numberProp("action=idle 的安静时长（毫秒），默认 500"),
                ),
            ),
        ),
    )

    // ---- 输入 ----
    add(
        ToolSpec(
            "click",
            "点击或长按。by=coord（默认）用 x/y 像素坐标；normalized=true 时 x/y 为 0~1 比例（推荐，免疫截图缩放）。by=text|id|desc 时按 value 匹配控件。long=true 长按。" +
                "坐标点击默认不做命中校验，结果含 hit_checked=false；需要命中的控件做自校验时传 hit=true（代价是一次控件树读取），结果会返回 hit 并置 hit_checked=true。" +
                "by=text 走 OCR 兜底时同样返回 hit_checked=false 与 hit_reason，控件树命中则为 true。",
            objectSchema(
                mapOf(
                    "by" to enumProp("定位方式", listOf("coord", "text", "id", "desc")),
                    "value" to stringProp("by=text/id/desc 时的匹配值"),
                    "x" to numberProp("横坐标"),
                    "y" to numberProp("纵坐标"),
                    "normalized" to booleanProp("x/y 是否为 0~1 归一化比例"),
                    "long" to booleanProp("是否长按"),
                    "duration_ms" to numberProp("长按时长（毫秒）"),
                    "settle" to booleanProp("点击后等待界面稳定再返回"),
                    "hit" to booleanProp("返回命中的控件（需读控件树，较慢；默认关闭）"),
                    "fuzzy" to booleanProp("by=text 时 OCR 是否按编辑距离容错，默认 true"),
                    "min_conf" to numberProp("by=text 时忽略置信度低于该值的 OCR 块（0~1，默认 0）"),
                    "dry_run" to booleanProp("只返回将要点击的位置，不真正执行"),
                ),
            ),
        ),
    )
    add(
        ToolSpec(
            "swipe",
            "从一点滑动到另一点，可用于滚动列表。normalized=true 时坐标为 0~1 比例。",
            objectSchema(
                mapOf(
                    "x1" to numberProp("起点横坐标"),
                    "y1" to numberProp("起点纵坐标"),
                    "x2" to numberProp("终点横坐标"),
                    "y2" to numberProp("终点纵坐标"),
                    "duration_ms" to numberProp("滑动时长（毫秒）"),
                    "normalized" to booleanProp("坐标是否为 0~1 归一化比例"),
                    "dry_run" to booleanProp("只返回将要滑动的起止点，不真正执行"),
                ),
                required = listOf("x1", "y1", "x2", "y2"),
            ),
        ),
    )
    add(
        ToolSpec(
            "gesture",
            "注入一条复杂手势轨迹（曲线 / 多段 / 多指 / 按住停顿）。strokes 为笔画数组，每个点可写 [x,y] 或 [x,y,时间ms]。",
            objectSchema(
                mapOf(
                    "strokes" to gestureStrokesProp("手势轨迹，例如 [[[100,200],[300,400,120]]]"),
                    "dry_run" to booleanProp("只回显将要注入的轨迹，不真正执行"),
                ),
                required = listOf("strokes"),
            ),
        ),
    )
    add(
        ToolSpec(
            "text",
            "输入文本。默认向当前焦点输入框输入；提供 by=id + value 时向匹配控件设值。" +
                "无障碍可用时用 ACTION_SET_TEXT，否则用控件树定位并聚焦后输入。" +
                "method 选择输入方式：auto（默认）/ input / clipboard（剪贴板粘贴，兼容性最好）/ broadcast。",
            objectSchema(
                mapOf(
                    "text" to stringProp("要输入的文本"),
                    "by" to enumProp("按控件属性设置", listOf("id")),
                    "value" to stringProp("by=id 时的控件 id"),
                    "method" to enumProp("输入方式", listOf("auto", "input", "clipboard", "broadcast")),
                    "dismiss_ime" to booleanProp("输入后收起软键盘；发送 ESC 失败时回退 back，并用 IME 状态校验后返回 ime_dismissed（需要 Shell 后端才能校验）"),
                    "dry_run" to booleanProp("只返回将要执行的操作，不真正输入"),
                ),
                required = listOf("text"),
            ),
        ),
    )
    add(
        ToolSpec(
            "press",
            "发送按键。key 可为 back / home / recents / enter / esc，或 Android KeyEvent 键码（整数，如返回=4、回车=66）。收软键盘一般用 esc（Shizuku/无线调试/Root 下 keyevent 111），back 往往无效。",
            objectSchema(
                mapOf(
                    "key" to anyKeyProp("按键名称或键码"),
                    "dry_run" to booleanProp("只返回将要发送的按键，不真正执行"),
                ),
                required = listOf("key"),
            ),
        ),
    )
    add(
        ToolSpec(
            "wait",
            "等待界面变化。for=text：等待出现包含 text 的文字；for=idle：等待界面稳定（控件树不再变化）。返回 ok 表示是否等到、matched 与耗时；无控件树时 idle 会降级为固定等待并标 degraded。",
            objectSchema(
                mapOf(
                    "for" to enumProp("等待类型", listOf("text", "idle")),
                    "text" to stringProp("for=text 时要等待的文字"),
                    "timeout_ms" to numberProp("超时（毫秒），默认 10000"),
                    "interval_ms" to numberProp("轮询间隔（毫秒），默认 400"),
                    "settle_ms" to numberProp("for=idle 时判定稳定的持续时长（毫秒），默认 600"),
                ),
            ),
        ),
    )
    add(
        ToolSpec(
            "app",
            "应用操作。action=launch：启动 package；action=open_url：用系统方式打开 url。",
            objectSchema(
                mapOf(
                    "action" to enumProp("操作", listOf("launch", "open_url")),
                    "package" to stringProp("action=launch 时的应用包名"),
                    "url" to stringProp("action=open_url 时的链接"),
                    "dry_run" to booleanProp("只返回将要执行的操作，不真正启动 / 打开"),
                ),
                required = listOf("action"),
            ),
        ),
    )
    add(
        ToolSpec(
            "batch",
            "顺序执行多个动作，一次调用完成，省去多轮往返。默认遇错即停并返回已完成步骤；" +
                "不适合把 ocr / ui_dump 等大输出放进批内。actions 为 [{tool,arguments}]。" +
                "批内若含多个会造成界面跳转的 UI 操作（点击 / 按键 / 滑动），后一步常会因前一步的过渡动画尚未结束而落空，" +
                "请给相关 click 传 settle=true 等待界面稳定。",
            objectSchema(
                mapOf(
                    "actions" to batchActionsProp(),
                    "continue_on_error" to booleanProp("遇错是否继续执行后续动作，默认 false（即失败即停）"),
                ),
                required = listOf("actions"),
            ),
        ),
    )
    if (shellAvailable) {
        add(
            ToolSpec(
                "shell",
                "执行 Shell 命令并返回结构化结果 {ok, stdout, stderr, exit_code, elapsed_ms}（需要 Shizuku 或 Root）。",
                objectSchema(mapOf("command" to stringProp("要执行的命令")), required = listOf("command")),
            ),
        )
    }
}

// ---- Schema 辅助 ----

private fun objectSchema(
    properties: Map<String, JSONObject>,
    required: List<String> = emptyList(),
): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } })
    if (required.isNotEmpty()) put("required", JSONArray(required))
}

private fun stringProp(description: String): JSONObject =
    JSONObject().put("type", "string").put("description", description)

private fun numberProp(description: String): JSONObject =
    JSONObject().put("type", "number").put("description", description)

private fun booleanProp(description: String): JSONObject =
    JSONObject().put("type", "boolean").put("description", description)

private fun enumProp(description: String, values: List<String>): JSONObject =
    JSONObject()
        .put("type", "string")
        .put("description", description)
        .put("enum", JSONArray(values))

/** key 允许字符串（名称）或整数（KeyEvent 键码）。 */
private fun anyKeyProp(description: String): JSONObject = JSONObject()
    .put("description", description)
    .put("type", JSONArray().put("string").put("integer"))

private fun gestureStrokesProp(description: String): JSONObject = JSONObject()
    .put("type", "array")
    .put("description", description)
    .put("items", JSONObject().put("type", "array").put("items", JSONObject().put("type", "number")))

/** batch 的动作数组：`[{tool, arguments}]`。 */
private fun batchActionsProp(): JSONObject = JSONObject()
    .put("type", "array")
    .put("description", "要顺序执行的动作列表")
    .put(
        "items",
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("tool", stringProp("工具名"))
                    .put(
                        "arguments",
                        JSONObject().put("type", "object").put("description", "该工具的参数"),
                    ),
            )
            .put("required", JSONArray().put("tool")),
    )
