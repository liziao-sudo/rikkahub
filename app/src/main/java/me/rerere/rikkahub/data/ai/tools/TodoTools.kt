package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 任务清单状态。生命周期 = 一次 generateText 的多步 loop。
 */
class TodoState {
    data class Todo(
        val id: String,
        val title: String,
        val status: String = "pending", // pending | in_progress | completed
        val detail: String? = null,
    )

    val todos = mutableListOf<Todo>()
}

/**
 * todo_write 工具：让模型把复杂任务拆成清单、逐项推进。
 * 全量覆盖语义（每次传入完整列表）。
 */
fun createTodoTools(json: Json, state: TodoState): List<Tool> = listOf(
    Tool(
        name = "todo_write",
        description = """
            Create and manage a structured task list for the current working session.
            Use this to break complex tasks into smaller steps and track progress.
            Always provide the full updated list (full overwrite semantics).
            status must be one of: pending, in_progress, completed.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("todos", buildJsonObject {
                        put("type", "array")
                        put("description", "The complete updated todo list")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("id", buildJsonObject { put("type", "string") })
                                put("title", buildJsonObject { put("type", "string") })
                                put("status", buildJsonObject {
                                    put("type", "string")
                                    put("description", "pending | in_progress | completed")
                                })
                                put("detail", buildJsonObject { put("type", "string") })
                            })
                            put("required", buildJsonObject {
                                put("type", "array")
                            })
                        })
                    })
                },
                required = listOf("todos"),
            )
        },
        needsApproval = { false },
        execute = { args ->
            runCatching {
                val todosJson = (args as? JsonObject)?.get("todos")?.jsonArray
                    ?: return@runCatching listOf(UIMessagePart.Text("todo_write: missing 'todos' array"))
                val parsed = todosJson.mapNotNull { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    TodoState.Todo(
                        id = id,
                        title = title,
                        status = o["status"]?.jsonPrimitive?.content ?: "pending",
                        detail = o["detail"]?.jsonPrimitive?.content,
                    )
                }
                state.todos.clear()
                state.todos.addAll(parsed)
                listOf(UIMessagePart.Text(renderTodos(state)))
            }.getOrElse {
                listOf(UIMessagePart.Text("todo_write error: ${it.message}"))
            }
        },
    )
)

private fun renderTodos(state: TodoState): String {
    if (state.todos.isEmpty()) return "Task list is empty."
    val sb = StringBuilder("Current task list:\n")
    state.todos.forEach { t ->
        val mark = when (t.status) {
            "completed" -> "[x]"
            "in_progress" -> "[~]"
            else -> "[ ]"
        }
        sb.appendLine("$mark ${t.title}")
        if (!t.detail.isNullOrBlank()) sb.appendLine("    ${t.detail}")
    }
    return sb.toString().trimEnd()
}
