package com.abrignoni.knowndatalogger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/** The view tree writer. Every text here is made up. */
class ViewTreeTest {
    private fun node(text: String?, id: String = "com.example.chat:id/message_text", password: Boolean = false,
                     desc: String? = null, children: List<VNode> = emptyList()) =
        VNode("android.widget.TextView", "com.example.chat", id, text, desc, "[0,0][100,50]",
            password = password, children = children)

    private val tree = node(null, id = "com.example.chat:id/list", children = listOf(
        node("my password is tulip-9, do not share it"),
        node("Your verification code is 482915"),
        node("the PIN is 4821"),
        node("hunter2-typed-secret", id = "com.example.chat:id/password_entry", password = true, desc = "also-secret"),
        node("a < b & \"c\" > d\nsecond line\ttab"),
    ))

    private fun parsed(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))

    @Test fun whatWasTypedInAPasswordFieldIsLeftOut() {
        val xml = ViewTree.xml(tree)
        assertFalse(xml.contains("hunter2-typed-secret"))
        assertFalse(xml.contains("also-secret"))
        assertEquals(1, ViewTree.secretFields(tree))
        // The field itself is still listed, marked as a password field, with its id.
        assertTrue(xml.contains("resource-id=\"com.example.chat:id/password_entry\""))
        assertTrue(xml.contains("password=\"true\""))
    }

    @Test fun textThatMentionsPasswordsOrCarriesCodesIsKeptAsShown() {
        val xml = ViewTree.xml(tree)
        assertTrue(xml.contains("my password is tulip-9, do not share it"))
        assertTrue(xml.contains("Your verification code is 482915"))
        assertTrue(xml.contains("the PIN is 4821"))
    }

    @Test fun theCodeFilterWouldHaveChangedThatText() {
        // Control: the notification filter does withhold this code, so the test above is not passing by accident.
        val (shown, masked) = NotificationMap.maskCodes("Your verification code is 482915")
        assertTrue(masked)
        assertFalse(shown!!.contains("482915"))
    }

    @Test fun outputIsWellFormedAndRoundTripsSpecialCharacters() {
        val doc = parsed(ViewTree.xml(tree))
        assertEquals("hierarchy", doc.documentElement.nodeName)
        val nodes = doc.getElementsByTagName("node")
        assertEquals(6, nodes.length)
        val texts = (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("text").nodeValue }
        assertTrue(texts.contains("a < b & \"c\" > d\nsecond line\ttab"))
        assertEquals("", texts[4])  // the password field
    }

    @Test fun controlCharactersAndUnpairedSurrogatesDoNotBreakTheFile() {
        val odd = node("bell\u0007 and half a pair \uD83D end")
        val doc = parsed(ViewTree.xml(odd))
        val text = doc.getElementsByTagName("node").item(0).attributes.getNamedItem("text").nodeValue
        assertTrue(text.startsWith("bell") && text.endsWith("end"))
    }

    @Test fun filesDetailNamesBothFilesAndTheirHashes() {
        val shot = Shot("e1", "e1.png", "aa", "ok", 1)
        val t = TreeFile("e1", "e1.xml", "bb", "ok", 1)
        val o = JSONObject(ViewTree.filesDetail(shot, t))
        assertEquals("aa", o.getJSONObject("screenshot").getString("sha256"))
        assertEquals("e1.xml", o.getJSONObject("view_tree").getString("file"))
        val only = JSONObject(ViewTree.filesDetail(shot, null))
        assertFalse(only.has("view_tree"))
    }

    @Test fun rowsCarryTheViewTreeAndTheChainIsNotChanged() {
        val s = Stamp(1_000, "UTC", 0, 100, 5, null, null)
        val e = Entry(seq = 1, entryId = "e1", revisionN = 0, kind = Kind.AUTO_EVENT, scenarioId = "sc", deviceLabel = "A",
            profileId = 1, stamp = s, editedUtcMs = 1_000, action = "mark")
        val t = TreeFile("e1", "e1.xml", "bb", "ok", 1)
        val o = e.toJson(null, t)
        assertEquals("bb", o.getJSONObject("view_tree").getString("sha256"))
        assertTrue(e.toJson().isNull("view_tree"))
        // The hashed form of a row does not include the file fields, so exports from 0.4.0 and from iOS still verify.
        assertFalse(Chain.fields(e, "").containsKey("view_tree"))
        assertFalse(Chain.fields(e, "").containsKey("screenshot"))
        val line = Csv.row(e, true, null, t).split(",")
        assertEquals("e1.xml", line[Csv.HEADER.indexOf("view_tree_file")])
        assertEquals("bb", line[Csv.HEADER.indexOf("view_tree_sha256")])
        assertNull(Csv.values(e, true)["view_tree_file"])
    }
}
