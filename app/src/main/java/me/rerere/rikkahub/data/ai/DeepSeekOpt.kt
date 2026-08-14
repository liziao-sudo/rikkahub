package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

/**
 * DeepSeek 专属优化的判定与开关。
 *
 * 所有优化能力实现成通用代码，但仅在 `isDeepSeek` 为 true 时启用，
 * 从而对 OpenAI / Claude / Gemini 等其它 provider 零影响。
 *
 * 判据（任一命中即视为 DeepSeek）：
 * 1. provider 的 baseUrl 含 "deepseek"
 * 2. model 的 modelId 含 "deepseek"
 * 3. model 的 providerOverwrite 的 baseUrl 含 "deepseek"
 */
object DeepSeekOpt {

    /** 是否对当前 model+provider 启用 DeepSeek 专属优化 */
    fun isDeepSeek(provider: ProviderSetting, model: Model): Boolean {
        if (provider.baseUrlContains("deepseek")) return true
        if (model.modelId.contains("deepseek", ignoreCase = true)) return true
        val overwrite = model.providerOverwrite
        if (overwrite != null && overwrite.baseUrlContains("deepseek")) return true
        return false
    }

    private fun ProviderSetting.baseUrlContains(kw: String): Boolean {
        val url = when (this) {
            is ProviderSetting.OpenAI -> baseUrl
            is ProviderSetting.Google -> baseUrl
            else -> ""
        }
        return url.contains(kw, ignoreCase = true)
    }
}
