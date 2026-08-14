package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * ask_user_question 工具：让模型在执行中途主动向用户提问澄清，
 * 而不是在信息不足或方向有歧义时瞎猜、一条道走到黑。
 *
 * 实现依赖 RikkaHub 已有的 [me.rerere.ai.ui.ToolApprovalState.Answered] 机制：
 * needsApproval 恒 true → 工具进入 Pending → 用户在审批界面作答 →
 * GenerationHandler 的 Answered 分支直接把用户答案作为工具输出回填。
 * 因此本工具的 execute 几乎不会被调用，仅作兜底。
 */
fun createAskUserTool(): Tool = Tool(
    name = "ask_user_question",
    description = """
        Ask the user a question to clarify requirements, resolve ambiguity,
        or confirm a decision before proceeding. Use this when you lack critical
        information that would change your approach, instead of guessing.
        Ask only essential questions; prefer making reasonable assumptions for minor details.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("question", buildJsonObject {
                    put("type", "string")
                    put("description", "The question to ask the user")
                })
                put("options", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional suggested answers for the user to choose from")
                })
            },
            required = listOf("question"),
        )
    },
    needsApproval = { true },
    execute = {
        listOf(
            UIMessagePart.Text("Question forwarded to the user.")
        )
    },
)
