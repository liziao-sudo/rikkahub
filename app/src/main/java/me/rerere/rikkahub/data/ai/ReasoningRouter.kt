package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel

/**
 * 任务感知思维模式路由 —— 移植自 dsh-router-standard（yjh051108/dsh-routing-suite）。
 *
 * 核心：分类第一条用户消息为 build(react) / fix(spec) / weak(内部路由)，
 * 据此动态调整 reasoning effort 与 persona，让 DeepSeek 合理调用思考强度。
 *
 * 关键（dsh-router-standard P11/P24 实测）：
 *  - weak（内部路由）是最优模式：模型自己按任务决定 build/fix 风格
 *  - Pro 的最优 weak persona = spec 句 + classify 指令，【不加 anchors】
 *    （anchors 对 Flash 有效 +5.67，但对 Pro 有害：suite-full 83% < naked 87.5%）
 *  - Flash 需要 recall/converge/anti-runaway 三锚
 */
object ReasoningRouter {

    /** react（构建/新建）关键词 */
    private val REACT_RE = Regex(
        "(开发|创建|写一个|生成|从零|做一个|游戏|网页|网站|构建|新项目|搭建|实现|做出|上线|落地|脚本|工具|应用|" +
            "build|create|develop|generate|implement|make a|new project)",
        RegexOption.IGNORE_CASE
    )

    /** spec（修复/维护）关键词 */
    private val SPEC_RE = Regex(
        "(修复|修一下|调试|重构|维护|排查|报错|出错|崩溃|优化|审查|review|fix|debug|refactor|maintain|repair|" +
            "broken|break|为什么|异常|故障|迁移|升级|兼容)",
        RegexOption.IGNORE_CASE
    )

    /**
     * 分类任务文本。
     * @return 1 = react（构建），0 = spec（修复），null = weak（内部路由）
     */
    fun classify(text: String): Int? {
        if (text.isBlank()) return null
        val react = REACT_RE.findAll(text).count()
        val spec = SPEC_RE.findAll(text).count()
        return when {
            react > spec -> 1
            spec > react -> 0
            else -> null
        }
    }

    /**
     * weak 模式的分类指令 —— Pro 最优（P24）。
     * 让模型在动手前先判断任务类型（build/fix）并采取匹配风格。
     */
    const val CLASSIFY_INSTRUCTION =
        "Before acting, decide the task type (build or fix) and adopt the matching style: " +
            "build → hands-on production; fix → inspect-and-plan."

    /**
     * 按分类决定 reasoning effort。仅当用户设为 AUTO 时路由；
     * 返回 null 表示保持 AUTO（weak 模式，模型自决定）。
     */
    fun reasoningFor(mode: Int?): ReasoningLevel? = when (mode) {
        1 -> ReasoningLevel.LOW   // react 构建：低思考，快速动手
        0 -> ReasoningLevel.HIGH  // spec 修复：高思考，先分析
        else -> null              // weak：保持 AUTO
    }
}
