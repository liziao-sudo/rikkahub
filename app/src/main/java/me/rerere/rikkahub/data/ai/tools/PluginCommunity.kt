package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件社区工具：对接 DSH 官方插件社区（GitHub topic `dsh-plugin`）。
 * agent 可浏览社区插件清单，参考其能力后用 write_plugin 落地实现。
 */
fun createCommunityTool(): Tool = Tool(
    name = "list_community_plugins",
    description = """
        Browse the official DeepSeek Harness plugin community (GitHub topic `dsh-plugin`).
        Returns plugin repositories sorted by stars with name, description, and URL.
        Use this to discover capabilities, then implement the ones you need locally
        via write_plugin (self-evolution).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword to filter the community search")
                })
            }
        )
    },
    needsApproval = { false },
    execute = { args ->
        runCatching {
            val keyword = (args as? kotlinx.serialization.json.JsonObject)
                ?.get("keyword")?.jsonPrimitive?.content.orEmpty()
            val query = if (keyword.isBlank()) "topic:dsh-plugin" else "topic:dsh-plugin+$keyword"
            val url = URL("https://api.github.com/search/repositories?q=$query&sort=stars&per_page=20")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().readText()
            val items = JSONObject(body).getJSONArray("items")
            if (items.length() == 0) {
                listOf(UIMessagePart.Text("No community plugins found."))
            } else {
                val sb = StringBuilder("DSH 插件社区（topic: dsh-plugin）：\n")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val fullName = item.getString("full_name")
                    val desc = item.optString("description", "")
                    val stars = item.optInt("stargazers_count", 0)
                    sb.append("- ${fullName} (⭐$stars): $desc\n")
                }
                listOf(UIMessagePart.Text(sb.toString().trimEnd()))
            }
        }.getOrElse {
            listOf(UIMessagePart.Text("community fetch error: ${it.message}"))
        }
    },
)
