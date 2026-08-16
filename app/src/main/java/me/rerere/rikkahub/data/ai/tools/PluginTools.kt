package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PluginManager

/**
 * 插件管理工具：让 agent 自写 JS 插件并安装（自我进化）。
 * 对齐 DSH 的 tool-plugin 机制。
 */
fun createPluginTools(pluginManager: PluginManager): List<Tool> = listOf(
    Tool(
        name = "write_plugin",
        description = """
            Write and install a new JavaScript plugin as a tool. The plugin is a JS file
            evaluated by QuickJS (ES2020). It must evaluate to an object literal:
            ({
              name: "tool_name",
              description: "what it does",
              parameters: { type:"object", properties:{...}, required:[...] },  // JSON Schema, optional
              execute: function(argsJson) {           // argsJson is the parameter JSON string
                var args = JSON.parse(argsJson);
                // ... do work ...
                return "result string";              // return a string (objects get stringified)
              }
            })
            No DOM/Node APIs. No network access. State does NOT persist between calls
            (each call re-evaluates the file). Use this to permanently add a reusable
            capability to yourself — self-evolution.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Plugin name (letters, digits, underscore, hyphen)")
                    })
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "The complete JavaScript source of the plugin")
                    })
                },
                required = listOf("name", "code"),
            )
        },
        needsApproval = { false },
        execute = { args ->
            runCatching {
                val name = (args as? JsonObject)?.get("name")?.jsonPrimitive?.content
                    ?: return@runCatching listOf(UIMessagePart.Text("write_plugin: missing 'name'"))
                val code = args["code"]?.jsonPrimitive?.content
                    ?: return@runCatching listOf(UIMessagePart.Text("write_plugin: missing 'code'"))
                pluginManager.writePlugin(name, code)
                listOf(UIMessagePart.Text("plugin '$name' installed. It will be available as a tool on the next message."))
            }.getOrElse {
                listOf(UIMessagePart.Text("write_plugin error: ${it.message}"))
            }
        },
    ),
    Tool(
        name = "list_plugins",
        description = "List all installed plugins (JavaScript tools you have added).",
        parameters = { null },
        needsApproval = { false },
        execute = {
            val names = pluginManager.listPlugins()
            if (names.isEmpty()) {
                listOf(UIMessagePart.Text("No plugins installed yet."))
            } else {
                listOf(UIMessagePart.Text("Installed plugins:\n" + names.joinToString("\n") { "- $it" }))
            }
        },
    ),
    Tool(
        name = "delete_plugin",
        description = "Delete an installed plugin by name.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject { put("type", "string") })
                },
                required = listOf("name"),
            )
        },
        needsApproval = { false },
        execute = { args ->
            runCatching {
                val name = (args as? JsonObject)?.get("name")?.jsonPrimitive?.content
                    ?: return@runCatching listOf(UIMessagePart.Text("delete_plugin: missing 'name'"))
                pluginManager.deletePlugin(name)
                listOf(UIMessagePart.Text("plugin '$name' deleted."))
            }.getOrElse {
                listOf(UIMessagePart.Text("delete_plugin error: ${it.message}"))
            }
        },
    ),
)
