package com.benton.izukijs.runtime.api

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.webkit.JavascriptInterface
import com.benton.izukijs.core.log.LogBus
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 传感器原始接口。挂在 `sensors` 命名空间。
 *
 * QuickJS 是单线程运行时，不能从 SensorManager 线程直接回调 JS。这里把传感器事件
 * 缓冲成队列，由脚本侧（兼容层定时器）调用 [pollRaw] 拉取；未标记兼容的脚本也可以
 * 直接使用 `registerRaw` + `pollRaw`。
 */
class SensorsApi(
    private val context: Context,
    private val logBus: LogBus,
) : Closeable {

    private val manager: SensorManager? by lazy {
        runCatching { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
            .getOrNull()
    }

    private val listeners = ConcurrentHashMap<String, SensorEventListener>()

    /** 传感器事件来自系统线程，用锁保护有界缓冲，容量与取出数量都精确可控。 */
    private val eventsLock = Any()
    private val events = ArrayDeque<String>()
    private val ids = AtomicInteger(0)

    @JavascriptInterface
    fun registerRaw(sensorName: String, delay: Int): String {
        val service = manager ?: return errorJson("无传感器服务")
        val name = sensorName.trim()
        if (name.isEmpty()) return errorJson("传感器名称不能为空")
        val sensor = findSensor(service, name)
            ?: return errorJson("不支持的传感器: $name")
        if (listeners.size >= MAX_LISTENERS) {
            return errorJson("同时注册的传感器过多（上限 $MAX_LISTENERS）")
        }
        val id = "sensor-" + ids.incrementAndGet()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                enqueue(changeJson(id, sensor.name, event))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                enqueue(accuracyJson(id, sensor.name, accuracy))
            }
        }
        val registered = runCatching {
            service.registerListener(listener, sensor, delay.coerceIn(0, MAX_DELAY_US))
        }.getOrDefault(false)
        if (!registered) {
            logBus.warn("传感器注册失败: ${sensor.name}")
            return errorJson("传感器注册失败: ${sensor.name}")
        }
        listeners[id] = listener
        return JSONObject().apply {
            put("ok", true)
            put("id", id)
            put("name", sensor.name)
            put("type", sensor.type)
        }.toString()
    }

    /** 取出缓冲区中的事件；每次最多 [MAX_POLL] 条。 */
    @JavascriptInterface
    fun pollRaw(): String {
        val drained = ArrayList<String>(MAX_POLL)
        synchronized(eventsLock) {
            while (drained.size < MAX_POLL) {
                drained += events.removeFirstOrNull() ?: break
            }
        }
        // JSON 解析放在锁外，避免阻塞传感器回调线程。
        val array = JSONArray()
        drained.forEach { item -> runCatching { array.put(JSONObject(item)) } }
        return array.toString()
    }

    @JavascriptInterface
    fun unregisterRaw(id: String): Boolean {
        val listener = listeners.remove(id) ?: return false
        runCatching { manager?.unregisterListener(listener) }
        return true
    }

    @JavascriptInterface
    fun unregisterAllRaw(): Boolean {
        val service = manager
        listeners.values.forEach { listener ->
            runCatching { service?.unregisterListener(listener) }
        }
        listeners.clear()
        synchronized(eventsLock) { events.clear() }
        return true
    }

    /** 设备提供的传感器列表，供脚本选择名称。 */
    @JavascriptInterface
    fun listRaw(): String {
        val service = manager ?: return "[]"
        val sensors = runCatching { service.getSensorList(Sensor.TYPE_ALL) }.getOrNull()
            ?: return "[]"
        val array = JSONArray()
        sensors.take(MAX_LIST).forEach { sensor ->
            array.put(
                JSONObject().apply {
                    put("name", sensor.name)
                    put("type", sensor.type)
                    put("stringType", sensor.stringType)
                    put("vendor", sensor.vendor ?: "")
                    put("version", sensor.version)
                },
            )
        }
        return array.toString()
    }

    override fun close() {
        unregisterAllRaw()
    }

    private fun enqueue(json: JSONObject) {
        val item = json.toString()
        synchronized(eventsLock) {
            // 丢弃最旧事件，保证缓冲区严格有界。
            if (events.size >= MAX_EVENTS) events.removeFirstOrNull()
            events.addLast(item)
        }
    }

    private fun changeJson(id: String, name: String, event: SensorEvent): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", name)
            put("kind", "change")
            put("accuracy", event.accuracy)
            put("timestamp", event.timestamp)
            put(
                "values",
                JSONArray().apply {
                    event.values.forEach { put(it.toDouble()) }
                },
            )
        }

    private fun accuracyJson(id: String, name: String, accuracy: Int): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", name)
            put("kind", "accuracy")
            put("accuracy", accuracy)
        }

    private fun findSensor(service: SensorManager, name: String): Sensor? {
        val upper = name.uppercase(Locale.ROOT)
        val type = SENSOR_TYPES[upper] ?: upper.toIntOrNull() ?: reflectType(upper)
        return type?.let { runCatching { service.getDefaultSensor(it) }.getOrNull() }
    }

    private fun reflectType(upper: String): Int? = runCatching {
        Sensor::class.java.getField("TYPE_$upper").getInt(null)
    }.getOrNull()

    private fun errorJson(message: String): String = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }.toString()

    private companion object {
        const val MAX_LISTENERS = 16
        const val MAX_EVENTS = 512
        const val MAX_POLL = 200
        const val MAX_LIST = 200
        const val MAX_DELAY_US = 1_000_000

        /** 与 Auto.js `sensors.register('名称')` 对齐的常用名称。 */
        val SENSOR_TYPES: Map<String, Int> = buildMap {
            put("ACCELEROMETER", Sensor.TYPE_ACCELEROMETER)
            put("MAGNETIC_FIELD", Sensor.TYPE_MAGNETIC_FIELD)
            put("ORIENTATION", Sensor.TYPE_ORIENTATION)
            put("GYROSCOPE", Sensor.TYPE_GYROSCOPE)
            put("LIGHT", Sensor.TYPE_LIGHT)
            put("PRESSURE", Sensor.TYPE_PRESSURE)
            put("TEMPERATURE", Sensor.TYPE_AMBIENT_TEMPERATURE)
            put("AMBIENT_TEMPERATURE", Sensor.TYPE_AMBIENT_TEMPERATURE)
            put("PROXIMITY", Sensor.TYPE_PROXIMITY)
            put("GRAVITY", Sensor.TYPE_GRAVITY)
            put("LINEAR_ACCELERATION", Sensor.TYPE_LINEAR_ACCELERATION)
            put("ROTATION_VECTOR", Sensor.TYPE_ROTATION_VECTOR)
            put("RELATIVE_HUMIDITY", Sensor.TYPE_RELATIVE_HUMIDITY)
            put("STEP_COUNTER", Sensor.TYPE_STEP_COUNTER)
            put("STEP_DETECTOR", Sensor.TYPE_STEP_DETECTOR)
            put("HEART_RATE", Sensor.TYPE_HEART_RATE)
            put("SIGNIFICANT_MOTION", Sensor.TYPE_SIGNIFICANT_MOTION)
        }
    }
}
