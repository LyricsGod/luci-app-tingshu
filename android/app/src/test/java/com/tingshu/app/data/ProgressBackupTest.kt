package com.tingshu.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressBackupTest {

    @Test
    fun `导出格式和网页端一致`() {
        val json = ProgressBackup.exportJson(
            mapOf(
                "net:三体" to Progress(1, 125.5, listOf(0), 1700000000000L),
                "net:空书" to Progress(),
            ),
            exportedAt = 123L,
        )
        val root = JSONObject(json)
        assertEquals("tingshu", root.getString("app"))
        assertEquals(1, root.getInt("version"))
        assertEquals(123L, root.getLong("exportedAt"))

        val items = root.getJSONObject("items")
        assertTrue(items.has("net:三体"))
        assertTrue(!items.has("net:空书")) // 空记录不导出
        val item = items.getJSONObject("net:三体")
        assertEquals(1, item.getInt("index"))
        assertEquals(125.5, item.getDouble("time"), 0.001)
        assertEquals(0, item.getJSONArray("done").getInt(0))
    }

    @Test
    fun `解析备份并忽略坏字段`() {
        val json = """
            {"app":"tingshu","version":1,"items":{
              "net:三体":{"index":2,"time":12.5,"done":[0,1,-3],"updatedAt":99},
              "net:明朝":{"index":0,"time":0,"done":[],"updatedAt":1}
            }}
        """
        val parsed = ProgressBackup.parse(json)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals(listOf(0, 1), parsed.getValue("net:三体").done)
        assertEquals(2, parsed.getValue("net:三体").index)
    }

    @Test
    fun `不是备份文件返回 null`() {
        assertNull(ProgressBackup.parse("{}"))
        assertNull(ProgressBackup.parse("not json"))
        assertNull(ProgressBackup.parse("""{"items":[]}"""))
    }

    @Test
    fun `导出文件名带时间戳`() {
        assertTrue(ProgressBackup.fileName(0L).startsWith("tingshu-progress-"))
        assertTrue(ProgressBackup.fileName().endsWith(".json"))
    }
}
