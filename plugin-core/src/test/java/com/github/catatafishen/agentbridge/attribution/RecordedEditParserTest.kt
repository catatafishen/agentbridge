package com.github.catatafishen.agentbridge.attribution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecordedEditParserTest {

    private fun parse(tool: String, json: String?) = RecordedEditParser.parse("t1", 5, tool, json)

    @Test
    fun `edit_text yields the new string as a partial edit`() {
        val e = parse("edit_text", """{"path":"A.java","old_str":"a","new_str":"b\nc"}""")!!
        assertEquals("b\nc", e.newText)
        assertFalse(e.wholeFile)
        assertEquals("t1", e.turnId)
        assertEquals(5, e.timestampMs)
    }

    @Test
    fun `symbol tools use their body or content`() {
        assertEquals("body", parse("replace_symbol_body", """{"new_body":"body"}""")!!.newText)
        assertEquals("c1", parse("insert_after_symbol", """{"content":"c1"}""")!!.newText)
        assertEquals("c2", parse("insert_before_symbol", """{"content":"c2"}""")!!.newText)
    }

    @Test
    fun `create_file and a plain write_file own the whole file`() {
        assertEquals(true, parse("create_file", """{"content":"x"}""")!!.wholeFile)
        assertEquals(true, parse("write_file", """{"content":"x"}""")!!.wholeFile)
    }

    @Test
    fun `write_file with a line range or old_str is a partial edit`() {
        assertFalse(parse("write_file", """{"content":"x","start_line":3,"end_line":4}""")!!.wholeFile)
        assertFalse(parse("write_file", """{"old_str":"a","new_str":"b"}""")!!.wholeFile)
    }

    @Test
    fun `truncated or invalid arguments are ignored`() {
        assertNull(parse("edit_text", """{"path":"A.java","new_str":"unterminated"""))
        assertNull(parse("edit_text", null))
        assertNull(parse("edit_text", ""))
        assertNull(parse("edit_text", "[]"))
    }

    @Test
    fun `calls without the inserted text and unknown tools are ignored`() {
        assertNull(parse("edit_text", """{"path":"A.java","old_str":"a"}"""))
        assertNull(parse("read_file", """{"path":"A.java"}"""))
    }
}
