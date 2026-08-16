package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

private const val TAG = "PluginManager"

/**
 * JS 插件管理器 —— 对齐 DSH 的 tool-plugin 机制。
 *
 * 插件 = 一个 JavaScript 文件（QuickJS 执行），约定导出对象：
 * ```javascript
 * ({
 *   name: "my_plugin",           // 工具名
 *   description: "do something", // 工具描述
 *   parameters: {                // JSON Schema（可选）
 *     type: "object",
 *     properties: { x: { type: "string" } },
 *     required: ["x"]
 *   },
 *   execute: function(argsJson) { // argsJson 是参数 JSON 字符串
 *     var args = JSON.parse(argsJson);
 *     return "result";            // 返回字符串（对象会被 stringify）
 *   }
 * })
 * ```
 *
 * 插件目录：`filesDir/plugins/<name>.js`。
 * execute 每次重新 evaluate，无共享状态、插件改完立即生效。
 */
class PluginManager(private val context: Context, private val json: Json) {

    private val pluginsDir: File
        get() = File(context.filesDir, "plugins").apply { mkdirs() }

    /** 已安装插件名列表 */
    fun listPlugins(): List<String> =
        pluginsDir.listFiles { f -> f.isFile && f.extension == "js" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    /** 写入插件文件（安全清理名字，防路径穿越） */
    fun writePlugin(name: String, content: String) {
        val safe = sanitize(name)
        File(pluginsDir, "$safe.js").writeText(content)
        Log.i(TAG, "writePlugin: $safe")
    }

    /** 删除插件 */
    fun deletePlugin(name: String) {
        val safe = sanitize(name)
        File(pluginsDir, "$safe.js").delete()
        Log.i(TAG, "deletePlugin: $safe")
    }

    fun readPlugin(name: String): String? =
        File(pluginsDir, "${sanitize(name)}.js").takeIf { it.exists() }?.readText()

    /** 加载所有插件为工具 */
    fun loadPlugins(): List<Tool> = listPlugins().mapNotNull { loadPlugin(it) }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

    /** 加载单个插件：读静态元信息（name/description/parameters），execute 时重新 evaluate */
    private fun loadPlugin(name: String): Tool? {
        val file = File(pluginsDir, "$name.js")
        if (!file.exists()) return null
        return runCatching {
            // 第一次 evaluate：读静态元信息
            val meta = evaluatePlugin(file.readText())
            val toolName = meta.name
            val description = meta.description
            val paramsSchema = meta.parameters

            Tool(
                name = toolName,
                description = description,
                parameters = { paramsSchema },
                needsApproval = { false },
                execute = { args ->
                    // 每次执行重新 evaluate，调用 execute 函数
                    runCatching {
                        val exec = loadExecutable(file.readText())
                        try {
                            val result = exec.executeFn.call(json.encodeToString(args))
                            val text = when (result) {
                                null -> "null"
                                is QuickJSObject -> exec.context.stringify(result)
                                else -> result.toString()
                            }
                            listOf(UIMessagePart.Text(text))
                        } finally {
                            exec.context.close()
                        }
                    }.getOrElse {
                        listOf(UIMessagePart.Text("plugin '$toolName' error: ${it.message}"))
                    }
                },
            )
        }.onFailure {
            Log.w(TAG, "loadPlugin $name failed: ${it.message}")
        }.getOrNull()
    }

    private data class PluginMeta(
        val name: String,
        val description: String,
        val parameters: InputSchema?,
    )

    private data class Executable(val context: QuickJSContext, val executeFn: JSFunction)

    /** 解析插件静态元信息 */
    private fun evaluatePlugin(source: String): PluginMeta {
        val context = QuickJSContext.create()
        return try {
            val obj = context.evaluate(source) as? QuickJSObject
                ?: error("plugin must evaluate to an object")
            val name = obj.getString("name") ?: error("plugin missing 'name'")
            val description = obj.getString("description") ?: ""
            val parameters = runCatching {
                val paramsObj = obj.getJSObject("parameters") ?: return@runCatching null
                val schemaJson = context.stringify(paramsObj)
                val schema = json.parseToJsonElement(schemaJson).jsonObject
                InputSchema.Obj(
                    properties = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap()),
                    required = (schema["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content }
                )
            }.getOrNull()
            PluginMeta(name, description, parameters)
        } finally {
            context.close()
        }
    }

    /** 加载插件的 execute 函数（每次执行调用，context 用完即关） */
    private fun loadExecutable(source: String): Executable {
        val context = QuickJSContext.create()
        return try {
            val obj = context.evaluate(source) as? QuickJSObject
                ?: error("plugin must evaluate to an object")
            val executeFn = obj.getJSFunction("execute")
                ?: error("plugin missing 'execute' function")
            Executable(context, executeFn)
        } catch (e: Throwable) {
            context.close()
            throw e
        }
    }
}
