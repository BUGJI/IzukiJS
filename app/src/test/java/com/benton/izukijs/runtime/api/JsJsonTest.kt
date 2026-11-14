package com.benton.izukijs.runtime.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsJsonTest {

    @Test
    fun errorResultHasUniformShape() {
        val json = JSONObject(JsJson.error("boom"))

        assertFalse(json.getBoolean("ok"))
        assertEquals("boom", json.getString("error"))
    }

    @Test
    fun errorExtrasAreMerged() {
        val json = JSONObject(
            JsJson.error("boom") {
                put("stage", "parse")
                put("retryable", true)
            },
        )

        assertEquals("parse", json.getString("stage"))
        assertTrue(json.getBoolean("retryable"))
    }

    @Test
    fun okResultHasOnlySuccessFlagByDefault() {
        val json = JSONObject(JsJson.ok())

        assertTrue(json.getBoolean("ok"))
        assertFalse(json.has("error"))
    }

    @Test
    fun okExtrasAreMerged() {
        val json = JSONObject(JsJson.ok { put("value", 1) })

        assertTrue(json.getBoolean("ok"))
        assertEquals(1, json.getInt("value"))
    }

    @Test
    fun errorMessageIsJsonEscaped() {
        val message = "line1\n\"quoted\""
        val json = JSONObject(JsJson.error(message))

        assertEquals(message, json.getString("error"))
    }
}
