package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * subagent 工具：把子任务委派给独立上下文的子代理。
 * 子代理在隔离的消息上下文中运行（不污染主代理上下文），
 * 完成后返回一份简洁的最终报告。
 *
 * [runSubagent] 由 GenerationHandler 提供，捕获子代理可用的工具集合
 * （不含 subagent 本身，避免无限递归）。
 */
fun createSubagentTool(
    runSubagent: suspend (String) -> String,
): Tool = Tool(
    name = "subagent",
    description = """
        Delegate a self-contained subtask to a fresh subagent with its own
        isolated context. The subagent works independently and returns a concise
        report. Use this to offload research, analysis, or a clearly-scoped chunk
        of work so it does not bloat the main conversation context.
        Do NOT use for trivial tasks you can do inline in one step.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "The complete self-contained task description for the subagent")
                })
            },
            required = listOf("prompt"),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val prompt = (args as? JsonObject)?.get("prompt")?.jsonPrimitive?.content
        if (prompt.isNullOrBlank()) {
            listOf(UIMessagePart.Text("subagent: missing 'prompt' argument"))
        } else {
            val report = runSubagent(prompt)
            listOf(UIMessagePart.Text(report))
        }
    },
)
