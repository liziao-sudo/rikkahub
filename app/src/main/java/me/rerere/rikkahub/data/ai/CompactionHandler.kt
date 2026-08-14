package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 上下文自动压缩（对齐 DSH 的 compaction）：
 * 当 DeepSeek 会话上下文超阈值时，把最旧的一段历史摘要成文本，
 * 摘要注入 system prompt，只保留最近的消息原样。
 *
 * 相比 RikkaHub 现有的 [limitContext]（直接丢弃最旧消息），
 * 摘要能保留任务进展/关键决策/待办，长任务不丢上下文。
 *
 * 注意：摘要调用走 providerImpl.generateText 直连（非 agent loop），
 * 避免递归。reasoningLevel 设为 OFF 以节省时间与 token。
 */
class CompactionHandler {
    data class CompactionResult(
        val summary: String?,
        val messages: List<UIMessage>,
    )

    /** 触发摘要的总字符阈值（约 75K token，DeepSeek 100 万上下文留足余量） */
    private val compactionTriggerChars = 300_000

    /** 摘要时保留的最近消息比例（0.0 ~ 1.0） */
    private val keepRatio = 0.4

    suspend fun maybeCompact(
        messages: List<UIMessage>,
        contextMessageLimit: Int,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        model: Model,
    ): CompactionResult {
        if (messages.isEmpty()) return CompactionResult(null, messages)

        val totalChars = messages.sumOf { msg -> msg.parts.sumOf { part -> partTextLength(part) } }
        val exceedsByCount = contextMessageLimit > 0 && messages.size > contextMessageLimit
        val exceedsByChars = totalChars > compactionTriggerChars

        if (!exceedsByCount && !exceedsByChars) {
            return CompactionResult(null, messages)
        }

        // 需要摘要的最旧部分（保留最近 keepRatio 的消息）
        val keepCount = (messages.size * keepRatio).toInt().coerceAtLeast(2)
        val summarizeCount = (messages.size - keepCount).coerceAtLeast(1)
        if (summarizeCount <= 0) return CompactionResult(null, messages)

        val toSummarize = messages.subList(0, summarizeCount)
        val kept = messages.subList(summarizeCount, messages.size)

        val summary = runCatching {
            generateSummary(toSummarize, providerImpl, provider, model)
        }.getOrNull() ?: return CompactionResult(null, messages)

        return CompactionResult(summary, kept)
    }

    private suspend fun generateSummary(
        toSummarize: List<UIMessage>,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        model: Model,
    ): String {
        val transcript = toSummarize.joinToString("\n\n") { msg ->
            val role = msg.role.name.lowercase()
            val text = msg.parts.joinToString(" ") { part -> partText(part) }
            "[$role] $text"
        }

        val prompt = """
            You are summarizing a long conversation to manage context length.
            Produce a concise summary (under 500 words) preserving:
            - key facts and constraints
            - decisions made and their rationale
            - task progress (what's done, what's in progress, what's pending)
            - any open questions or next steps
            Write the summary in the same language as the conversation.
            Do not include a preamble.

            CONVERSATION:
            $transcript
        """.trimIndent()

        val result = providerImpl.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )
        return result.message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .take(4000)
    }

    private fun partTextLength(part: UIMessagePart): Int = when (part) {
        is UIMessagePart.Text -> part.text.length
        is UIMessagePart.Reasoning -> 0 // reasoning 不重复计入
        is UIMessagePart.Tool -> part.input.length + part.output.sumOf { partTextLength(it) }
        else -> 0
    }

    private fun partText(part: UIMessagePart): String = when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Reasoning -> ""
        is UIMessagePart.Tool -> "[tool:${part.toolName}] ${part.output.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }}"
        else -> ""
    }
}
