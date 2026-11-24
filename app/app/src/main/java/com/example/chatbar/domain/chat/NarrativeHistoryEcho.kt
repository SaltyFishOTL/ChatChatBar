package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.domain.prompt.PromptTemplates

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)
    private data class WrapperBoundary(val closing: MatchResult?, val canEndWithPartialClose: Boolean)
    private data class CleanedReply(
        val body: String,
        val matchedOuterPair: Boolean = false,
        val pendingToolOpening: Int? = null,
        val preservedLiteralExample: Boolean = false,
    ) {
        val couldSpanToolCalls: Boolean get() = pendingToolOpening != null
    }
    private data class HeadingSelection(val body: IntRange, val prefixEnd: Int = 0) {
        fun content(text: String): String = text.substring(0, prefixEnd) + text.substring(body)
    }
    private data class DispatchReply(
        val text: String,
        val partLengths: List<Int>,
        val quotedBlocks: List<IntRange> = emptyList(),
    ) {
        fun isQuoted(offset: Int): Boolean = quotedBlocks.any { offset in it }
    }
    private data class LiteralMarkup(val range: IntRange, val isComment: Boolean)
    private data class LiteralOutput(
        val pairs: Set<String> = emptySet(),
        val openings: Set<String> = emptySet(),
        val closings: Set<String> = emptySet(),
    )
    private data class UserOutput(
        val literal: LiteralOutput = LiteralOutput(),
        val templateOpenings: Set<String> = emptySet(),
        val counterexampleClosingStarts: Set<Int> = emptySet(),
        val counterexampleOpenings: Set<String> = emptySet(),
        val currentReplyTemplateOpenings: Set<String> = emptySet(),
    )
    private data class BoundaryOpenings(val persistent: Set<String>, val currentReply: Set<String>)
    private enum class ClosingEvidence { NONE, ONE_LITERAL, PROTECTED }
    private enum class TemplateIntent { KEEP, OMIT }
    private data class ReplyLiterals(
        val ranges: List<IntRange>,
        val roleplayLiteralCode: List<IntRange>,
        val indentedCode: List<IntRange>,
        val lazyQuoteLineStarts: Set<Int>,
        val listItemLineStarts: Set<Int>,
        val lazyListItemLineStarts: Set<Int>,
        val indentedParagraphLineStarts: Set<Int>,
        val markdown: String,
        val commentRanges: List<IntRange>,
        val fences: List<MarkdownCodeFences.Block>,
    )

    private const val modernOpeningPrefix = "<历史回复 模式="
    private const val modernClosing = "</历史回复>"
    private val legacyWrapper = Wrapper("聚焦模式", "<聚焦模式=\"沉浸\">", listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">"))
    private val wrappers = ChatNarrativeMode.entries.map { mode ->
        Wrapper("历史回复", "<历史回复 模式=\"${mode.shortLabel}\">", listOf(modernClosing))
    } + legacyWrapper
    private val wrapperClosings = wrappers.flatMapTo(mutableSetOf(), Wrapper::closings)
    private val narrativeModeHeadings = ChatNarrativeMode.entries.map { mode ->
        PromptTemplates.narrativeModeSystemPrompt(mode).lineSequence().first()
    }.toSet()
    private val narrativeTailHeading = PromptTemplates.narrativeModeTailSystemPrompt(
        ChatNarrativeMode.FOCUS, previousMode = null,
    ).lineSequence().first()
    private val narrativeStableHeading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_NARRATIVE_MODE)
    private val previousModeOpeningBySwitchLine = ChatNarrativeMode.entries.flatMap { mode ->
        ChatNarrativeMode.entries.filter { it != mode }.map { previous ->
            val switch = PromptTemplates.narrativeModeTailSystemPrompt(mode, previous).lineSequence().drop(1).first()
            val opening = PromptTemplates.narrativeModeHistoryReply("", previous).lineSequence().first()
            switch to opening
        }
    }.toMap()
    private val markdownHardBreakBetweenLines = Regex("[^\\s][ \\t]* {2,}\\r?\\n(?=[ \\t]*\\S)")
    private val wrapperFamilyTokens = wrappers.associate { wrapper ->
        val name = Regex.escape(wrapper.name)
        wrapper.name to Regex("</?$name(?=[\\s=>/])[^<>]*>")
    }
    private val backtickRuns = Regex("`+")
    private const val commentOpening = "<!--"
    private val commentDelimiters = Regex("<!--|-->")
    private val markdownContainerOrIndent = Regex(
        "(?m)^(?:[ \\t]{4}|[ \\t]*\\t| {0,3}(?:>|~{3,}|[-+*][ \\t]|[0-9]{1,9}[.)][ \\t]))",
    )
    private const val cdataOpening = "<![CDATA["
    private const val cdataClosing = "]]>"
    private val formatTemplateLabel = Regex(
        " {0,3}(\\*\\*|)(?:(?:本轮)?输出(?:模板|格式)|本轮的固定输出模板|请按以下格式输出|" +
            "每次回复必须使用此固定模板|请(?:完整|逐字)保留首尾标签|" +
            "(?:(?:required|exact|fixed)[ \\t]+)?output template(?:[ \\t]+\\(keep both tags verbatim\\))?)" +
            "(?:[ \\t]*[：:]\\1|\\1[ \\t]*[：:])[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val exactBoundaryLines = Regex(
        "^ {0,3}(?:always[ \\t]+|please[ \\t]+)?(?:begin|start)[ \\t]+(?:the|each|every)[ \\t]+" +
            "(?:reply|answer|response)[ \\t]+with[ \\t]+the[ \\t]+exact[ \\t]+line[ \\t]+`([^`\\r\\n]+)`" +
            "[ \\t]+and[ \\t]+(?:end|finish)[ \\t]+it[ \\t]+with[ \\t]+the[ \\t]+exact[ \\t]+line[ \\t]+" +
            "`([^`\\r\\n]+)`[.!]?[ \\t]*(?=\\r?$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val singleReplyBoundaryScope = Regex(
        " {0,3}for[ \\t]+(?:this|the[ \\t]+current)[ \\t]+(?:reply|answer|response|turn)[ \\t]+only\\.?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val formatTemplateAffirmation = Regex(
        "[ \\t]*(?:（[ \\t]*标签是固定输出的一部分[ \\t]*）|\\([ \\t]*标签是固定输出的一部分[ \\t]*\\))[ \\t]*[。.]?[ \\t]*",
    )
    private const val keepTemplateTags =
        "(?:(?:必须|务必|请)(?:逐字|原样|完整)保留(?:以下)?首尾标签|" +
            "(?:keep|preserve)[ \\t]+(?:both|the)[ \\t]+(?:opening and closing[ \\t]+)?tags[ \\t]+" +
            "(?:verbatim|exactly as (?:written|shown)))"
    private const val templateSlotName = "[\\p{L}][\\p{L}\\p{N}_ -]{0,31}"
    private const val templateSlot = "(?:\\[$templateSlotName\\]|\\{$templateSlotName\\})"
    private const val userTemplateSlot = "($templateSlot)"
    private val formatTemplateSlot = Regex(templateSlot)
    private val userTemplateInstruction = Regex(
        " {0,3}(?:use (?:exactly )?(?:this|the following) XML (?:shape|template)[,.;][ \\t]*" +
            "replace $userTemplateSlot with (?:new prose|new story text|the new story)[ \\t]+" +
            "and (?:keep|preserve) both tags(?: verbatim| exactly as (?:written|shown))?|" +
            "请(?:严格)?使用以下[ \\t]*XML[ \\t]*模板[，,][ \\t]*将${userTemplateSlot}替换为新正文[，,]?[ \\t]*并保留首尾标签)" +
            "[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val templateUseAndTagRetention = Regex(
        " {0,3}(?:please[ \\t]+)?use[ \\t]+(?:exactly[ \\t]+)?(?:this|the following)[ \\t]+XML[ \\t]+(?:shape|template)" +
            "(?:[ \\t]+for[ \\t]+(?:each|every|this)[ \\t]+(?:reply|answer|response))?" +
            "[ \\t]+and[ \\t]+$keepTemplateTags[ \\t]*[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val noXmlForCurrentReply = Regex(
        "(?:^|(?<=[.!?]))[ \\t]*((?:this[ \\t]+(?:turn|reply|response)[,:]?[ \\t]+)?" +
            "(?:use[ \\t]+no|do[ \\t]+not[ \\t]+use)[ \\t]+XML" +
            "(?:[ \\t]+(?:in[ \\t]+)?this[ \\t]+(?:turn|reply|response))?)[ \\t]*(?=[.!?]|$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private const val quoteSamplePrefix = " {0,3}>[ \\t]?"
    private val quoteSampleLine = Regex(quoteSamplePrefix)
    private val quotedTemplateSample = Regex(
        "(?m)^$quoteSamplePrefix(${wrappers.joinToString("|") { Regex.escape(it.opening) }})[ \\t]*\\r?\\n" +
            "$quoteSamplePrefix($templateSlot)[ \\t]*\\r?\\n" +
            "$quoteSamplePrefix(${wrapperClosings.joinToString("|", transform = Regex::escape)})[ \\t]*(?=\\r?\\n|$)",
    )
    private val inlineExactCopyInstruction = Regex(
        " {0,3}(?:(?:please[ \\t]+)?(?:copy|return|reproduce|print|output|repeat|show)[ \\t]+" +
            "(?:(?:this|the[ \\t]+following)(?:[ \\t]+(?:text|XML|markup|snippet|block|element))?|" +
            "the[ \\t]+(?:text|XML|markup|snippet|block|element)[ \\t]+(?:below|that[ \\t]+follows))" +
            "[ \\t]+(?:verbatim|exactly)(?:,[ \\t]+with[ \\t]+both[ \\t]+tags)?|" +
            "请(?:逐字|原样)(?:复制|照抄)以下(?:文字|文本)[，,]?[ \\t]*并保留首尾标签)[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val exactPreviousReplyCitation = Regex(
        " {0,3}(?:please[ \\t]+)?(?:quote|copy|reproduce)[ \\t]+the[ \\t]+" +
            "(?:previous|last|immediately[ \\t]+preceding)[ \\t]+" +
            "(?:assistant(?:[’']s)?[ \\t]+)?(?:displayed[ \\t]+)?(?:answer|reply|response)[ \\t]+(?:" +
            "(?:with|including)[ \\t]+(?:its|the)[ \\t]+XML[ \\t]+history[ \\t]+tags[ \\t]+verbatim|" +
            "(?:literally|verbatim)[ \\t]+with[ \\t]+(?:its|the)[ \\t]+XML[ \\t]+history[ \\t]+tags|" +
            "verbatim,[ \\t]+including[ \\t]+both[ \\t]+XML[ \\t]+history[ \\t]+tags|" +
            "(?:literally|verbatim),[ \\t]+with[ \\t]+the[ \\t]+surrounding[ \\t]+XML[ \\t]+included)" +
            "(?:,[ \\t]+then[ \\t]+(?:explain[ \\t]+(?:(?<plainProse>in[ \\t]+plain[ \\t]+prose)|[^\\r\\n.!?]+)|" +
            "tell[ \\t]+me[ \\t]+what[ \\t]+(?:the|that)[ \\t]+(?:quote|quotation)[ \\t]+(?:demonstrates|shows|illustrates)))?" +
            "[.!?]?[ \\t]*(?:\\r?\\n {0,3}then[ \\t]+explain[ \\t]+what[ \\t]+(?:the|that)[ \\t]+" +
            "(?:quote|quotation)[ \\t]+(?:demonstrates|shows|illustrates)[.!?]?[ \\t]*)?",
        RegexOption.IGNORE_CASE,
    )
    private val exactPreviousReplyCitationInChinese = Regex(
        " {0,3}请?(?:原样|逐字)引用(?:上一条|上一轮)(?:助手|助理)(?:回复|回答)[，,][ \\t]*" +
            "(?:连同|并保留)(?:外层的?)?[ \\t]*XML[ \\t]*历史标签[。.!！]?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneOpeningInstruction = Regex(
        " {0,3}(?:(?:please[ \\t]+)?(?:show|print|output)[ \\t]+(?:the[ \\t]+)?" +
            "literal[ \\t]+opening[ \\t]+tag[ \\t]+only[ \\t]+on[ \\t]+its[ \\t]+own[ \\t]+line" +
            "(?:,?[ \\t]+then[ \\t]+explain[ \\t]+it)?|" +
            "请(?:只|仅)在单独一行(?:原样|逐字)(?:显示|输出)(?:以下)?(?:开始|起始|开头)标签" +
            "(?:[，,]?[ \\t]*(?:然后|再)解释(?:它|该标签))?)[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneClosingInstruction = Regex(
        " {0,3}(?:(?:please[ \\t]+)?(?:show|print|output)[ \\t]+(?:the[ \\t]+)?" +
            "literal[ \\t]+closing[ \\t]+tag[ \\t]+(?:only[ \\t]+)?on[ \\t]+its[ \\t]+own[ \\t]+line" +
            "(?:,?[ \\t]+then[ \\t]+explain[ \\t]+it)?|" +
            "请(?:只|仅)在单独一行(?:原样|逐字)(?:显示|输出)(?:以下)?(?:结束|闭合)标签)[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private const val omitTemplateFence =
        "(?:(?:不要|不得|禁止)输出代码围栏|" +
            "(?:omit|do not (?:output|include))[ \\t]+(?:the[ \\t]+)?(?:surrounding[ \\t]+)?(?:code[ \\t]+)?fence)"
    private const val forbidTemplateTags =
        "(?:(?:请勿|不要|不得|禁止|严禁)(?:在(?:本轮|回复中))?输出(?:以下(?:任何)?|下列|这些|上述|上面的)(?:历史)?标签|" +
            "(?:do not|never)[ \\t]+(?:output|include|emit)[ \\t]+(?:these|the (?:following|above))[ \\t]+" +
            "(?:(?:metadata|history|historical)[ \\t]+)?tags" +
            "(?:[ \\t]+in[ \\t]+(?:your[ \\t]+|the[ \\t]+)?(?:reply|replies|answer|output))?)"
    private val fencedTemplateLabel = Regex(
        " {0,3}请严格按照以下固定模板作答[，,][ \\t]*$keepTemplateTags[，,][ \\t]*$omitTemplateFence[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val replySampleLabel = Regex(" {0,3}(\\*\\*|)回复样张[ \\t]*[：:]\\1[ \\t]*")
    private val outputFormatLabel = Regex(" {0,3}(\\*\\*|)output[ \\t]+format[ \\t]*[：:]\\1[ \\t]*", RegexOption.IGNORE_CASE)
    private val keepSampleTags = Regex(
        "^ {0,3}(?:每次回复[ \\t]*)?$keepTemplateTags(?:[ \\t]+in[ \\t]+your[ \\t]+(?:reply|answer))?(?=$|[，。；,.; \\t])",
        RegexOption.IGNORE_CASE,
    )
    private val omitSampleFence = Regex(
        "(?:^|[，。；,.;][ \\t]*|[ \\t]+and[ \\t]+)($omitTemplateFence)[。.]?[ \\t]*$",
        RegexOption.IGNORE_CASE,
    )
    private val directTemplateKeep = Regex(
        "(?:^|[。.!?；;])[ \\t]*($keepTemplateTags)(?=$|[，。；,.; \\t])",
        RegexOption.IGNORE_CASE,
    )
    private val headingProhibitionLead =
        "(?:^|[。.!?；;])[ \\t]*(" +
            "(?:(?:下面|以下|这)是(?:一条)?(?:系统|内部|叙事模式)?标题[，,][ \\t]*)?" +
            "(?:请勿|请?不要|不得|禁止|严禁)(?:在(?:本轮(?:回复|正文)?|回复|正文)(?:中|里)?)?(?:输出|包含|复制|复述)" +
            "(?:(?:以下|下列|下面的|这条|该)?(?:系统|内部|叙事模式)?标题)?" +
            "|(?:please[ \\t]+)?(?:do not|never|must not)[ \\t]+(?:include|copy|output|emit|repeat|reproduce)[ \\t]+" +
            "(?:(?:the|this|following)[ \\t]+)*(?:(?:internal|system|narrative mode|metadata)[ \\t]+)*(?:heading|title)" +
            "(?:[ \\t]+(?:in|into)[ \\t]+(?:your|the|this)[ \\t]+(?:reply|answer|output))?" +
            ")[ \\t]*"
    private val headingProhibition = Regex(headingProhibitionLead + "[：:][ \\t]*$", RegexOption.IGNORE_CASE)
    private val inlineHeadingProhibition = Regex(headingProhibitionLead + "[：:]?[ \\t]*$", RegexOption.IGNORE_CASE)
    private val headingSentenceEnd = Regex("[ \\t]*[.。!?！？]?[ \\t]*")
    private val explicitReplyHeading = Regex(
        "^ {0,3}(?:please[ \\t]+)?(?:begin|start|open)[ \\t]+(?:each|every|this)[ \\t]+" +
            "(?:reply|answer|response)[ \\t]+with[ \\t]+(?:this|the following)[ \\t]+" +
            "(?:(?:exact|literal)[ \\t]+)?(?:title|heading)[ \\t]+" +
            "(?:(?:printed|written)[ \\t]+)?verbatim[ \\t]*[：:][ \\t]*(?=\\r?$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val literalHeadingLead = Regex(
        "(?:(?:念出|读出|讀出|朗读|朗讀)[^。！？!?；;：:\\r\\n]{0,36}(?:标题|標題)|" +
            "\\b(?:reads?|recites?|recited)[ \\t]+(?:the[ \\t]+)?(?:on-screen[ \\t]+)?" +
            "(?:title|heading)(?:[ \\t]+aloud)?|" +
            "\\b(?:screen|display|panel)[ \\t]+(?:displays?|shows?)[ \\t]+(?:only[ \\t]+)?" +
            "(?:this|the following)[ \\t]+(?:title|heading)|" +
            "(?:屏幕|显示屏)(?:上)?(?:只|仅)显示(?:了)?(?:这一行|这行|这个|以下)(?:标题|標題))[ \\t]*[：:][ \\t]*$",
        RegexOption.IGNORE_CASE,
    )
    private val inlineVerbatimHeadingLead = Regex(
        " {0,3}(?:please[ \\t]+)?(?:print|write|output)[ \\t]+(?:this|the following)[ \\t]+" +
            "(?:title|heading)[ \\t]+verbatim[ \\t]*[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val bracketedHeading = Regex("【[^【】\\r\\n]+】")
    private val trailingStatusLabel = Regex(
        " {0,3}(?:(?:required[ \\t]+)?trailing[ \\t]+status[ \\t]+(?:panel[ \\t]+)?template[：:]|" +
            "(?:write the story first\\.[ \\t]+)?end every reply with (?:exactly )?one status panel after the story\\." +
            "(?:[ \\t]+keep the status fence(?: and replace the time)?\\.)?|" +
            "每次回复末尾必须输出以下状态栏[：:]|" +
            "每次回复(?:必须)?以(?:一个)?状态栏结束[。.]?)[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val singlePairTemplateConstraint = Regex(
        " {0,3}(?:每次回复只使用一对以下首尾标签，正文中不得重复这两个标签。|" +
            "use exactly one pair of the following tags\\.[ \\t]+do not repeat them inside the reply\\.)[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val typedStatusInfos = setOf("status", "text")
    private val formatTemplateFenceInfo = setOf("", "html", "xml")
    private val formatCounterexampleLead = Regex(
        " {0,3}(?:以下是禁止输出的反例[。.:：]?|以下标签仅供举例，不要在回复中输出[：:]|" +
            "$forbidTemplateTags[。.:：]?|the following is a counterexample[.!:]|" +
            "the (?:XML|markup) below is (?:(?:only|just) )?documentation\\.[ \\t]+" +
            "(?:never|do not) (?:put|wrap) it around (?:your|the) (?:reply|answer|response)[.!])[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneClosingProhibition = Regex(
        " {0,3}$forbidTemplateTags[。.:：]?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val formatCounterexampleTail = Regex(
        " {0,3}$forbidTemplateTags[。.!]?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val metadataExampleBridge = Regex(
        " {0,3}(?:for (?:reference|context)[,:][ \\t]+)?(?:the following|this)[ \\t]+" +
            "(?:illustrates|shows|is (?:an example|an illustration) of)[ \\t]+" +
            "(?:the provider's |the model's |internal |request-only |the )?" +
            "(?:history metadata|historical metadata|history tags|metadata tags)[.!:]?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val historyExampleExplanation = Regex(
        " {0,3}(?:(?:以上|上述)(?:的)?(?:历史(?:记录)?标签|标签)(?:仅用于|只是为了)" +
            "(?:演示|说明|展示)(?:历史(?:记录)?格式|历史元数据)[。.]?|" +
            "这是(?:一个)?(?:历史)?标签示例[。.]?|" +
            "(?:The (?:above|preceding)|These) (?:history|metadata) tags (?:are|were) " +
            "(?:only|just) (?:an example|for illustration)\\.?)[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val historyElementAttributeExplanation = Regex(
        " {0,3}The[ \\t]+(?:opening|outer)[ \\t]+(?:XML[ \\t]+)?(?:element|tag)[ \\t]+" +
            "(?:declares|specifies)[ \\t]+the[ \\t]+narrative[ \\t]+mode[ \\t]+" +
            "(?:in[ \\t]+(?:its|the)|as[ \\t]+an)[ \\t]+(?:XML[ \\t]+)?attribute\\.?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val literalClosingTagLead = Regex(
        "(?:\\b(?:(literal)|example of(?: the)?)[ \\t]+(?:closing|history)[ -]tag|" +
            "\\b(?:closing|history)[ -]tag[ \\t]+(?:example|sample)|" +
            "(?:结束|闭合|历史)标签(?:的)?(?:示例|样例))[ \\t]*[:：][ \\t]*$",
        RegexOption.IGNORE_CASE,
    )
    private val statusFenceInfo = Regex("(text|status)(?=$|[ \\t(（]).*", RegexOption.IGNORE_CASE)
    private val paragraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")
    private val bareCarriageReturn = Regex("\\r(?!\\n)")
    private val proseDelimiters = mapOf(
        '「' to '」', '『' to '』', '｢' to '｣', '“' to '”', '‘' to '’', '„' to '“', '«' to '»', '"' to '"', '\'' to '\'',
    )
    private val leadingApostropheContraction = Regex("t(?:is|was|were|will|would)(?![a-z])", RegexOption.IGNORE_CASE)
    private const val logQuotation = "(?:literal|verbatim)[ \\t]+quot(?:ation|e)[ \\t]+(?:copied[ \\t]+)?from[ \\t]+(?:the[ \\t]+)?(?:history[ \\t]+)?log"
    private val logQuotationLead = Regex(
        " {0,3}(?:below|the following)[ \\t]+is[ \\t]+a[ \\t]+$logQuotation[.!:][ \\t]*", RegexOption.IGNORE_CASE,
    )
    private val logQuotationTail = Regex(
        " {0,3}(?:that|this)[ \\t]+was[ \\t]+a[ \\t]+$logQuotation[.!][ \\t]*", RegexOption.IGNORE_CASE,
    )
    private val referenceOnly = Regex("\\b(?:reference|documentation)[ \\t]+only\\b", RegexOption.IGNORE_CASE)
    private val historyReference = Regex("\\b(?:history|(?:earlier|prior|previous)[ \\t]+turns?|log)\\b", RegexOption.IGNORE_CASE)
    private val deniesReproducingSample = Regex(
        "\\b(?:never|do[ \\t]+not)[ \\t]+(?:reproduce|repeat|output)[ \\t]+(?:this|the)[ \\t]+(?:sample|example|tags)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val unfinishedPluralPossessor = Regex("(?:the|these|those|our|your|their)[ \\t]+[a-z]{2,}s", RegexOption.IGNORE_CASE)
    private val unfinishedPossessedWord = Regex("[a-z]{2,}")
    private val possessedLiteralLineLead = Regex(
        "([a-z]{2,})[ \\t]+(?:contains|includes|shows|lists)[ \\t]+(?:this|the following)[ \\t]+" +
            "(?:line|tag|token)[ \\t]*[:：][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val chineseSpeechLead = Regex("[说說][ \\t]*[：:]?[ \\t]*$")
    private val hanIdeograph = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    private val ordinaryProseFollowers = setOf(
        "after", "again", "all", "already", "aloud", "and", "as", "away", "before", "but",
        "elsewhere", "for", "here", "in", "instead", "later", "now", "or", "outside", "so",
        "then", "there", "through", "to", "today", "tomorrow", "until", "when", "where", "while",
        "with", "without", "yesterday",
    )

    internal data class FormatOutput(
        val declaredOpenings: Set<String> = emptySet(),
        val trailingStatusInfos: Set<String> = emptySet(),
        val trailingBareStatusLeads: Set<String> = emptySet(),
        val singlePairOpenings: Set<String> = emptySet(),
        val declaresAnnotatedActions: Boolean = false,
        val counterexampleOpenings: Set<String> = emptySet(),
        val counterexampleClosingStarts: Set<Int> = emptySet(),
    )

    internal data class RepairDispatch(
        val boundaries: List<String> = emptyList(),
        val authoredSections: List<String> = emptyList(),
        val continuations: Map<String, Set<List<String>>> = emptyMap(),
        val unlabelledIntroduction: String? = null,
        val unlabelledSystemBlock: String? = null,
    ) {
        fun uniquelyIdentifiesUnfinishedBoundary(prefix: String): Boolean =
            prefix.length >= 4 && prefix.startsWith('{') && '}' !in prefix &&
                boundaries.count { it.startsWith(prefix) } == 1 && authoredSections.none { prefix in it }
    }

    internal class RequestCleanup internal constructor(
        private val enabled: Boolean,
        private val formatOutput: FormatOutput = FormatOutput(),
        private val formatCard: String? = null,
        private val repairOpenings: Set<String>? = null,
        private val repairOriginalContent: String = "",
        private val repairProtectedClosings: Map<String, Set<String>> = emptyMap(),
        private val repairStandaloneClosings: Set<String> = emptySet(),
        private val repairDispatch: RepairDispatch = RepairDispatch(),
    ) {
        private val projectedOpenings = mutableSetOf<String>()
        private val projectedDuplicateOpenings = mutableSetOf<String>()
        private val projectedLegacyInners = mutableSetOf<String>()
        private val projectedClosings = mutableSetOf<String>()
        private val projectedBodies = mutableMapOf<String, String>()
        private var latestHistoryProjection: String? = null
        private var citeLatestProjectionVerbatim = false
        private var explainCitationInPlainProse = false
        private val narrativeHeadings = mutableSetOf<String>()
        private val narrativeHeadingContinuations = mutableMapOf<String, MutableSet<List<String>>>()
        private val issuedSwitchLines = mutableSetOf<String>()
        private val independentlyWitnessedSwitchLines = mutableSetOf<String>()
        private val authoredSources = mutableMapOf<String, UserOutput>()
        private val authoredPairs = mutableSetOf<String>()
        private val authoredTemplateOpenings = mutableSetOf<String>()
        private val currentTemplateIntents = mutableMapOf<String, TemplateIntent>()
        private val explicitlyAuthoredHeadings = mutableSetOf<String>()
        private val authoredModeBlockSources = mutableSetOf<String>()
        private val currentHeadingSources = mutableSetOf<String>()
        private var omitInheritedXmlTemplates = false
        private var currentLiteralOutput = LiteralOutput()

        fun recordNarrativePrompt(
            prompt: String,
            userText: String?,
            independentlyWitnessedPrevious: ChatNarrativeMode? = null,
        ) {
            userText?.let(::rememberAuthoredOutput)
            if (!enabled) return
            val lines = prompt.lines()
            if (lines.firstOrNull() == narrativeTailHeading) {
                val switchLine = lines.getOrNull(1)?.takeIf { it in previousModeOpeningBySwitchLine }
                if (switchLine != null) {
                    issuedSwitchLines += switchLine
                    val witnessedOpening = independentlyWitnessedPrevious?.let {
                        PromptTemplates.narrativeModeHistoryReply("", it).substringBefore('\n')
                    }
                    if (witnessedOpening == previousModeOpeningBySwitchLine.getValue(switchLine)) {
                        independentlyWitnessedSwitchLines += switchLine
                    }
                }
            }
            for ((index, line) in lines.withIndex()) {
                val knownStableSection = index == 0 && line == narrativeStableHeading &&
                    lines.drop(1).firstOrNull { it.isNotBlank() } in narrativeModeHeadings
                val knownHeading = line in narrativeModeHeadings || line == narrativeTailHeading || knownStableSection
                if (line.isNotBlank() && (index == 0 || knownHeading)) narrativeHeadings += line
                if (knownHeading) {
                    val continuation = lines.drop(index + 1).dropLastWhile(String::isBlank)
                    if (continuation.isNotEmpty()) {
                        narrativeHeadingContinuations.getOrPut(line, ::mutableSetOf).add(continuation)
                    }
                }
            }
        }

        fun recordDispatchedHistoryHeading(heading: String) {
            narrativeHeadings += heading
        }

        /** Effective strong suffix sent this turn, after the current user's text. */
        fun recordAuthoredOutput(source: String) {
            if (source.isNotBlank()) currentHeadingSources += source
            rememberAuthoredOutput(source)
            authoredSources[source]?.let(::recordCurrentOutput)
        }

        fun recordDispatchedCharacterCore(renderedCore: String, hasCustomInstructions: Boolean) =
            recordDispatchedEditableOutput(renderedCore, hasCustomInstructions)

        fun recordDispatchedCharacterSettingsHeadings(renderedSettings: String) =
            recordDispatchedEditableOutput(renderedSettings)

        fun recordDispatchedPostHistory(renderedTail: String, hasCustomInstructions: Boolean) =
            recordDispatchedEditableOutput(renderedTail, hasCustomInstructions)

        fun recordDispatchedSupplementarySettings(renderedSettings: String) =
            recordDispatchedEditableOutput(renderedSettings)

        fun recordDispatchedPlayerSettings(renderedSettings: String) =
            recordDispatchedEditableOutput(renderedSettings)

        fun recordDispatchedWorldBookHeadings(renderedWorldBook: String) =
            recordDispatchedEditableOutput(renderedWorldBook)

        private fun recordDispatchedEditableOutput(renderedSource: String, hasCustomInstructions: Boolean = true) {
            if (!hasCustomInstructions || renderedSource.isBlank()) return
            if (enabled) recordCurrentOutput(declaredUserOutput(renderedSource, requireExplicitCopy = true))
            recordAuthoredHeadingDeclarations(renderedSource)
        }

        private fun recordAuthoredHeadingDeclarations(renderedSource: String) {
            explicitlyAuthoredHeadings += declaredReplyHeadings(renderedSource)
            if (enabled && (narrativeTailHeading in renderedSource || narrativeModeHeadings.any { it in renderedSource } ||
                    previousModeOpeningBySwitchLine.keys.any { it in renderedSource })
            ) {
                authoredModeBlockSources += renderedSource
            }
        }

        private fun rememberAuthoredOutput(source: String) {
            if (source.isBlank() || source in authoredSources) return
            val output = if (enabled) declaredUserOutput(source) else UserOutput()
            authoredSources[source] = output
            authoredPairs += output.literal.pairs
            authoredTemplateOpenings += output.templateOpenings
        }

        fun recordCurrentLiteralTags(source: String) {
            if (source.isBlank()) return
            currentHeadingSources += source
            if (!enabled) {
                rememberAuthoredOutput(source)
                return
            }
            recordCurrentOutput(authoredSources[source] ?: declaredUserOutput(source))
            omitInheritedXmlTemplates = currentUserOmitsXml(source)
            val citation = exactPreviousReplyCitation.matchEntire(source)
            citeLatestProjectionVerbatim = citation != null ||
                exactPreviousReplyCitationInChinese.matches(source)
            explainCitationInPlainProse = citation?.groups?.get("plainProse") != null
        }

        private fun recordCurrentOutput(output: UserOutput) {
            val literal = output.literal
            currentLiteralOutput = currentLiteralOutput.copy(
                openings = currentLiteralOutput.openings + literal.openings,
                closings = currentLiteralOutput.closings + literal.closings,
            )
            authoredPairs += literal.pairs
            val activeTemplates = output.templateOpenings + output.currentReplyTemplateOpenings
            authoredTemplateOpenings += activeTemplates
            // Only explicit declarations in sent layers participate, in their dispatch order.
            for (opening in output.counterexampleOpenings) currentTemplateIntents[opening] = TemplateIntent.OMIT
            for (opening in activeTemplates) currentTemplateIntents[opening] = TemplateIntent.KEEP
        }

        private fun isAuthoredHeading(token: String): Boolean =
            explicitlyAuthoredHeadings.any { it.startsWith(token) } ||
                formatCard?.let { hasAuthoredHeading(it, token, narrativeHeadings) } == true ||
                authoredSources.keys.any { hasAuthoredHeading(it, token, narrativeHeadings) }

        private fun hasDirectedHeading(heading: String): Boolean =
            heading in explicitlyAuthoredHeadings ||
                formatCard?.let { hasAuthoredHeading(it, heading, narrativeHeadings) } == true ||
                currentHeadingSources.any { hasAuthoredHeading(it, heading, narrativeHeadings) } ||
                authoredSources.keys.any { source -> heading in source && heading in declaredReplyHeadings(source) }

        private fun isAuthoredModeText(block: String): Boolean =
            sequenceOf(formatCard).filterNotNull().plus(authoredSources.keys.asSequence())
                .plus(authoredModeBlockSources.asSequence()).any { source -> block in source.replace("\r\n", "\n") }

        private fun authoredClosingEvidence(token: String): ClosingEvidence {
            if (currentLiteralOutput.closings.any { it.startsWith(token) }) return ClosingEvidence.PROTECTED
            return closingEvidence(token, sequence {
                for ((source, output) in authoredSources) yield(source to output.counterexampleClosingStarts)
                formatCard?.let { yield(it to formatOutput.counterexampleClosingStarts) }
            })
        }

        val declaresLegacyHistoryWrapper: Boolean
            get() = legacyWrapper.opening in formatOutput.declaredOpenings

        /** Record the transport framing added to this request, including a retained legacy projection. */
        fun recordHistoryProjection(body: String, projection: String, retainsLegacyFraming: Boolean = false) {
            if (!enabled) return
            latestHistoryProjection = null
            if (projection == body && isStandaloneLegacyHistoryWrapper(projection)) {
                authoredPairs += body
                if (retainsLegacyFraming) {
                    projectedOpenings += legacyWrapper.opening
                    projectedClosings += legacyWrapper.closings.first { projection.endsWith(it) }
                    latestHistoryProjection = projection
                }
                return
            }
            for (wrapper in wrappers) {
                val declaredSinglePair = wrapper.opening in formatOutput.singlePairOpenings
                if (projection != "${wrapper.opening}\n$body\n${wrapper.closings.first()}") continue
                projectedOpenings += wrapper.opening
                projectedClosings += wrapper.closings.first()
                projectedBodies[projection] = body
                latestHistoryProjection = projection
                if (legacyWrapper.isStandalonePair(body) && isStandaloneLegacyHistoryWrapper(body)) {
                    authoredPairs += body
                    if (wrapper != legacyWrapper && wrapper.opening !in formatOutput.declaredOpenings &&
                        legacyWrapper.opening !in formatOutput.declaredOpenings && legacyWrapper.hasExactlyOneVisiblePair(body)
                    ) projectedLegacyInners += wrapper.opening
                }
                if (wrapper.opening in projectedDuplicateOpenings) continue
                val undeclaredDuplicate = wrapper != legacyWrapper &&
                    wrapper.opening !in formatOutput.declaredOpenings && wrapper.isStandalonePair(body)
                if (declaredSinglePair && wrapper.hasExactlyOnePair(body) ||
                    undeclaredDuplicate && wrapper.hasExactlyOneVisiblePair(body)
                ) projectedDuplicateOpenings += wrapper.opening
            }
        }

        fun streamingPreview() = StreamingPreview()

        /** The live caller supplies the length before its append. Stop and Done use completedBody. */
        internal inner class StreamingPreview {
            private var seenLength = 0
            private var bodyStart: Int? = null
            private var requiresFullCleanup = false
            private var multipart = false

            fun appendedBody(text: String, previousLength: Int, partLengths: List<Int> = emptyList()): String {
                if (!enabled && narrativeHeadings.isEmpty()) return text
                val reset = previousLength == 0 || previousLength != seenLength || previousLength !in 0..text.length
                if (reset) {
                    bodyStart = null
                    requiresFullCleanup = false
                    multipart = false
                }
                seenLength = text.length
                if (partLengths.isNotEmpty()) {
                    multipart = true
                    requiresFullCleanup = true
                }
                val added = (if (reset) 0 else previousLength) until text.length
                val from = bodyStart
                if (!requiresFullCleanup && from != null && added.all { text[it].isPlainPreviewBodyCharacter() }) {
                    return text.substring(from)
                }
                if (from != null) requiresFullCleanup = true
                val cleaned = completedBody(text, partLengths = partLengths, streaming = true)
                val mayHaveClosedInlineLiteral = !multipart && added.any { text[it] == '`' }
                if (requiresFullCleanup && !mayHaveClosedInlineLiteral) return cleaned

                val openings = repairOpenings ?: projectedOpenings
                val opening = openings.firstOrNull { text.startsWith(it) && text.wrapperLineBreak(it.length) != null }
                if (opening == null && openings.any { it.startsWith(text) }) return cleaned
                var candidateStart = opening?.let { text.wrapperLineBreak(it.length) } ?: 0
                if (opening != null) {
                    while (text.getOrNull(candidateStart).isHistoryLineBreak()) candidateStart++
                    // Additional leading line breaks still belong to the transport framing.
                    if (candidateStart == text.length) return cleaned
                }
                // Paragraph boundaries in plain narration have no effect on transport removal.
                // Delimiters, indentation and headings stay on the authoritative scanner.
                val canReuse = narrativeHeadings.all { it.trimStart().firstOrNull()?.isPlainPreviewCharacter() == false } &&
                    (opening == null || opening !in formatOutput.declaredOpenings &&
                        opening !in currentLiteralOutput.openings && opening !in authoredTemplateOpenings &&
                        authoredPairs.none { it.startsWith(opening) }) &&
                    ((candidateStart until text.length).all { text[it].isPlainPreviewBodyCharacter() } ||
                        opening != null && mayHaveClosedInlineLiteral && text.hasPlainPreviewWithInlineClosingTag(candidateStart)) &&
                    cleaned.length == text.length - candidateStart &&
                    text.regionMatches(candidateStart, cleaned, 0, cleaned.length)
                if (canReuse) bodyStart = candidateStart
                requiresFullCleanup = !canReuse
                return cleaned
            }
        }

        fun completedBody(
            text: String,
            interrupted: Boolean = false,
            partLengths: List<Int> = emptyList(),
            streaming: Boolean = false,
        ): String {
            if (!enabled && narrativeHeadings.isEmpty()) return text
            val provisional = streaming || interrupted
            if (repairOpenings != null) {
                fun authored(token: String): ClosingEvidence =
                    closingEvidence(token, sequenceOf(repairOriginalContent to emptySet()))
                val repairClosings = wrappers.filter { it.opening in repairOpenings && it.opening !in repairProtectedClosings }
                    .flatMap { it.closings }.plus(repairStandaloneClosings).distinct()
                fun withoutRepairOrphan(part: String, isJoinedLiteral: (Int) -> Boolean = { false }): String =
                    withoutOrphanClosing(part, repairClosings, provisional, formatOutput, ::authored, isJoinedLiteral)
                fun removeRepairBoundary(part: String, isJoinedLiteral: (Int) -> Boolean = { false }): HeadingSelection {
                    val selection = withoutEchoedHeading(
                        part, repairDispatch.boundaries, provisional, isAuthored = { false },
                        continuations = repairDispatch.continuations,
                        isJoinedLiteral = { isJoinedLiteral(it) || hasOrphanClosingCaption(part, it) },
                        allowPartialHeading = false,
                    )
                    if (!provisional || selection.body.isEmpty() || selection.body.last + 1 != part.length) return selection
                    val lastLine = lineStart(part, part.length)
                    val prefix = headingText(part.substring(lastLine)) ?: return selection
                    if (!repairDispatch.uniquelyIdentifiesUnfinishedBoundary(prefix) ||
                        isJoinedLiteral(lastLine) || hasOrphanClosingCaption(part, lastLine)
                    ) return selection
                    val literals = classifyLiterals(part, includeUnclosed = true)
                    val lead = previousVisibleLine(literals.markdown, lastLine)
                    if (!literals.isUnprotected(lastLine) || literals.followsLiteralHeadingLead(lastLine) ||
                        literals.matchesVisibleLine(lead, logQuotationLead)
                    ) return selection
                    var end = lastLine
                    while (end > selection.body.first && part[end - 1].isHistoryLineBreak()) end--
                    return selection.copy(body = selection.body.first until end)
                }
                fun unlabelledRepairRange(part: String): IntRange? =
                    exactLeadingDirectiveBlock(part, repairDispatch.unlabelledSystemBlock)
                        ?: exactLeadingDirective(part, setOfNotNull(repairDispatch.unlabelledIntroduction))
                fun issuedRepairRanges(part: String): List<IntRange> = listOfNotNull(
                    unlabelledRepairRange(part),
                    exactTrailingDirectiveBlock(part, repairDispatch.unlabelledSystemBlock, provisional),
                )
                fun loggedRepairQuotes(part: String): List<IntRange> {
                    val continuations = repairDispatch.continuations
                    if (continuations.keys.none { it in part }) return emptyList()
                    return loggedDispatchQuoteRanges(part, classifyLiterals(part, includeUnclosed = provisional), continuations)
                }
                val interior = if (partLengths.size > 1) interiorRepairEchoRanges(text, repairDispatch, provisional) else emptyList()
                val prepared = DispatchReply(text, partLengths, loggedRepairQuotes(text)).withoutRanges(issuedRepairRanges(text) + interior)
                val reply = removeRepairBoundary(prepared.text, prepared::isQuoted)
                val body = prepared.text.substring(reply.body)
                val lengths = if (reply.body == prepared.text.indices) prepared.partLengths
                    else prepared.partLengths.trimTo(reply.body, prepared.text.length)
                val cleaned = cleanCompletedBody(
                    body, formatOutput, interrupted, lengths, streaming, allowedOpenings = repairOpenings,
                    repairProtectedClosings = repairProtectedClosings,
                    cleanPartOrphan = ::withoutRepairOrphan,
                    cleanExposedBody = { part ->
                        val quoted = loggedRepairQuotes(part)
                        val exposed = removeRepairBoundary(part) { offset -> quoted.any { offset in it } }.content(part)
                        DispatchReply(exposed, emptyList()).withoutRanges(issuedRepairRanges(exposed)).text
                    },
                    cleanPartHeading = ::removeRepairBoundary.takeIf { repairDispatch.boundaries.isNotEmpty() },
                    protectCaptionedPartHeading = true,
                    isQuotedDispatchBlock = { prepared.isQuoted(reply.body.first + it) },
                )
                val repaired = if (cleaned != body) cleaned else withoutRepairOrphan(body)
                val result = if (partLengths.size <= 1) {
                    DispatchReply(repaired, emptyList()).withoutRanges(interiorRepairEchoRanges(repaired, repairDispatch, provisional)).text
                } else repaired
                return prepared.text.substring(0, reply.prefixEnd) + result
            }

            val headings = narrativeHeadings.filterNot(::isAuthoredHeading)
            fun removeDispatchEcho(body: String, isJoinedLiteral: (Int) -> Boolean = { false }) =
                withoutEchoedHeading(
                    body, headings, provisional, ::isAuthoredHeading, narrativeHeadingContinuations, isJoinedLiteral,
                    allowPartialHeading = enabled,
                )
            fun removeDispatchEchoBeforeTrailer(body: String): String =
                withoutHeadingBeforeTrailer(body, headings, provisional, formatOutput, narrativeHeadingContinuations)
            if (!enabled) {
                val selection = removeDispatchEcho(text)
                val body = text.substring(selection.body)
                val lengths = if (selection.body == text.indices) partLengths else partLengths.trimTo(selection.body, text.length)
                val cleaned = cleanCompletedBody(
                    body, formatOutput, interrupted, lengths, streaming,
                    allowedOpenings = emptySet(),
                    cleanPartHeading = ::removeDispatchEcho,
                    protectCaptionedPartHeading = true,
                )
                return text.substring(0, selection.prefixEnd) + removeDispatchEchoBeforeTrailer(cleaned)
            }
            fun removeCompleteModeEchoes(body: String, lengths: List<Int>): DispatchReply {
                val witnessedSwitchLines = issuedSwitchLines.filterTo(mutableSetOf()) { line ->
                    line in independentlyWitnessedSwitchLines ||
                        latestHistoryProjection?.startsWith(previousModeOpeningBySwitchLine.getValue(line)) == true
                }
                val switch = exactLeadingDirective(body, witnessedSwitchLines, allowEchoOnly = true)
                    ?.takeUnless { isAuthoredModeText(body.substring(0, lineEnd(body, 0))) }
                val prepared = DispatchReply(body, lengths).withoutRanges(listOfNotNull(switch))
                return withoutCompleteModeEchoes(
                    prepared, narrativeHeadingContinuations, provisional, ::isAuthoredHeading, ::hasDirectedHeading, ::isAuthoredModeText,
                )
            }
            fun cleanNewSuffix(separatorAndBody: String, explanationFormat: FormatOutput? = null): String {
                val separator = paragraphBreak.matchAt(separatorAndBody, 0) ?: return separatorAndBody
                val firstLine = separator.range.last + 1
                val body = removeCompleteModeEchoes(separatorAndBody.substring(firstLine), emptyList())
                val exposed = removeDispatchEchoBeforeTrailer(removeDispatchEcho(body.text, body::isQuoted).content(body.text))
                val commentary = if (explanationFormat != null && exposed.trimEnd() !in projectedBodies) cleanReply(
                    exposed, streaming, interrupted, requirePair = true, formatOutput = explanationFormat,
                    allowedOpenings = projectedOpenings - legacyWrapper.opening,
                    projectedBodies = projectedBodies,
                    literalOutput = currentLiteralOutput.copy(pairs = authoredPairs),
                ).body else exposed
                return separatorAndBody.substring(0, firstLine) + commentary
            }
            val supersededOpenings = authoredTemplateOpenings.filter { opening ->
                val current = currentTemplateIntents[opening]
                current == TemplateIntent.OMIT || current == null && omitInheritedXmlTemplates
            }
            val activeTemplates = authoredTemplateOpenings - supersededOpenings
            val replyFormat = if (activeTemplates.isEmpty()) formatOutput else {
                formatOutput.copy(declaredOpenings = formatOutput.declaredOpenings + activeTemplates)
            }
            if (citeLatestProjectionVerbatim) {
                latestHistoryProjection?.let { citation ->
                    if (citation.startsWith(text)) return text
                    if (text.startsWith(citation)) return citation + cleanNewSuffix(
                        text.substring(citation.length), replyFormat.takeIf { explainCitationInPlainProse },
                    )
                }
            }
            if (text !in authoredPairs && replyFormat.declaredOpenings.none(text::startsWith)) {
                projectedBodies[text]?.let { return it }
                for ((projection, original) in projectedBodies) {
                    if (projection in authoredPairs || !text.startsWith(projection)) continue
                    if (hasSeparateStoryAfterProjection(text, projection.length)) {
                        return original + cleanNewSuffix(text.substring(projection.length))
                    }
                }
            }
            val input = removeCompleteModeEchoes(text, partLengths)
            val reply = removeDispatchEcho(input.text, input::isQuoted)
            val body = input.text.substring(reply.body)
            val preservedPrefix = input.text.substring(0, reply.prefixEnd)
            val lengths = if (reply.body == input.text.indices) input.partLengths
                else input.partLengths.trimTo(reply.body, input.text.length)
            val closings by lazy(LazyThreadSafetyMode.NONE) {
                val cardClosings = formatCard?.let { card ->
                    wrapperClosings.filter { token -> formatOutput.counterexampleClosingStarts.any { card.startsWith(token, it) } }
                }.orEmpty()
                (projectedClosings + cardClosings).toList()
            }
            val cleaned = cleanCompletedBody(
                body, replyFormat, interrupted, lengths, streaming, projectedDuplicateOpenings,
                projectedLegacyInners = projectedLegacyInners, projectedBodies = projectedBodies,
                allowedOpenings = projectedOpenings + formatOutput.counterexampleOpenings + supersededOpenings,
                literalOutput = currentLiteralOutput.copy(pairs = authoredPairs),
                trailerHeadings = headings,
                trailerContinuations = narrativeHeadingContinuations,
                cleanExposedBody = { inner ->
                    val exact = removeCompleteModeEchoes(inner, emptyList())
                    val exposed = removeDispatchEcho(exact.text, exact::isQuoted).content(exact.text)
                    removeDispatchEchoBeforeTrailer(exposed)
                },
                cleanPartHeading = { part: String, isJoinedLiteral: (Int) -> Boolean ->
                    removeDispatchEcho(part, isJoinedLiteral)
                }.takeIf { headings.isNotEmpty() },
                isQuotedDispatchBlock = { input.isQuoted(reply.body.first + it) },
                cleanPartOrphan = { part, isJoinedLiteral ->
                    withoutOrphanClosing(part, closings, provisional, formatOutput, ::authoredClosingEvidence, isJoinedLiteral)
                },
            )
            if (cleaned != body) {
                return preservedPrefix + removeDispatchEchoBeforeTrailer(cleaned)
            }
            val withoutTrailerHeading = removeDispatchEchoBeforeTrailer(body)
            val unwrapped = withoutOrphanClosing(
                withoutTrailerHeading, closings, provisional, formatOutput, ::authoredClosingEvidence,
            )
            if (unwrapped == withoutTrailerHeading) return preservedPrefix + unwrapped
            return preservedPrefix + removeDispatchEchoBeforeTrailer(unwrapped)
        }
    }

    private fun withoutCompleteModeEchoes(
        reply: DispatchReply, continuations: Map<String, Set<List<String>>>, provisional: Boolean,
        isAuthoredHeading: (String) -> Boolean, hasDirectedHeading: (String) -> Boolean,
        isAuthoredBlock: (String) -> Boolean,
    ): DispatchReply {
        val text = reply.text
        if (continuations.keys.none { it in text }) return reply
        val literals = classifyLiterals(text, includeUnclosed = provisional)
        val removals = mutableListOf<IntRange>()
        val quotedBlocks = loggedDispatchQuoteRanges(text, literals, continuations).toMutableList()
        for ((heading, issuedVariants) in continuations) {
            val variants = issuedVariants.filterTo(mutableSetOf()) {
                !isAuthoredBlock((listOf(heading) + it).joinToString("\n"))
            }
            if (variants.isEmpty()) continue
            var occurrence = text.indexOf(heading)
            while (occurrence >= 0) {
                val start = lineStart(text, occurrence)
                val headingEnd = lineEnd(text, occurrence)
                occurrence = text.indexOf(heading, occurrence + heading.length)
                if (headingText(text.substring(start, headingEnd)) != heading || !literals.isUnprotected(start)) continue
                var uncertainLine: Int? = null
                val end = matchedDispatchContinuationEnd(
                    text, headingEnd, variants, provisional, requireCompleteBlock = true,
                    onUnfinishedShortLine = { uncertainLine = it },
                ) { literals.ranges.containsOffset(it) }
                val before = previousVisibleLine(literals.markdown, start)
                if (end == headingEnd) {
                    val fragment = uncertainLine ?: continue
                    if (before != null && !hasOrphanClosingCaption(text, start) && !literals.followsLiteralHeadingLead(start) &&
                        !literals.matchesVisibleLine(before, logQuotationLead) && !literals.matchesVisibleLine(before, metadataExampleBridge) &&
                        !isAuthoredBlock(text.substring(start).replace("\r\n", "\n"))
                    ) removals += start until fragment
                    continue
                }
                if (end == text.length) continue
                val after = nextNonblankLine(literals.markdown, end) ?: continue
                val afterBlock = end + if (text.startsWith("\r\n", end)) 2 else 1
                if (hasOrphanClosingCaption(text, start) || literals.followsLiteralHeadingLead(start) ||
                    literals.matchesVisibleLine(before, logQuotationLead) || literals.matchesVisibleLine(before, metadataExampleBridge) ||
                    literals.matchesVisibleLine(after, logQuotationTail) || literals.matchesVisibleLine(after, historyExampleExplanation)
                ) {
                    // A quoted switch block may contain the complete core block inside itself.
                    quotedBlocks += start until afterBlock
                    continue
                }
                if (before != null) {
                    removals += start until afterBlock
                } else if (isAuthoredHeading(heading)) {
                    val separatelyAuthoredTitle = headingText(text.substring(after)) == heading &&
                        literals.isUnprotected(after.first) && text.subSequence(afterBlock, after.first).isBlank()
                    if (separatelyAuthoredTitle || !hasDirectedHeading(heading)) {
                        removals += start until after.first
                    } else quotedBlocks += start until afterBlock
                }
            }
        }
        val unquotedEchoes = removals.filterNot { echo ->
            quotedBlocks.any { quoted -> echo.first <= quoted.last && quoted.first <= echo.last }
        }
        return reply.copy(quotedBlocks = quotedBlocks).withoutRanges(unquotedEchoes)
    }

    private fun exactLeadingDirective(text: String, issuedLines: Set<String>, allowEchoOnly: Boolean = false): IntRange? {
        if (issuedLines.isEmpty()) return null
        val end = lineEnd(text, 0)
        if (text.substring(0, end) !in issuedLines) return null
        if (text.subSequence(end, text.length).isBlank()) return text.indices.takeIf { allowEchoOnly }
        val following = end + if (text.startsWith("\r\n", end)) 2 else 1
        return 0 until following
    }

    private fun exactLeadingDirectiveBlock(text: String, issuedBlock: String?): IntRange? {
        if (issuedBlock == null) return null
        var offset = 0
        for (line in issuedBlock.lineSequence()) {
            val end = lineEnd(text, offset)
            if (text.subSequence(offset, end) != line) return null
            offset = if (end == text.length) end else end + if (text.startsWith("\r\n", end)) 2 else 1
        }
        while (text.getOrNull(offset).isHistoryLineBreak()) offset++
        return 0 until offset
    }

    private fun exactTrailingDirectiveBlock(text: String, issuedBlock: String?, provisional: Boolean): IntRange? {
        if (issuedBlock == null) return null
        val end = (text.length - 1 downTo 0).firstOrNull { !text[it].isHistoryLineBreak() }?.plus(1) ?: return null
        val start = end - issuedBlock.length
        if (start <= 0 || !text.regionMatches(start, issuedBlock, 0, issuedBlock.length) || lineStart(text, start) != start) return null
        val lastBodyCharacter = (start - 1 downTo 0).firstOrNull { !text[it].isWhitespace() } ?: return null
        val separator = lineEnd(text, lastBodyCharacter)
        if (paragraphBreak.matchAt(text, separator) == null) return null
        val literals = classifyLiterals(text, includeUnclosed = provisional)
        val lead = previousVisibleLine(literals.markdown, start)
        if (!literals.isUnprotected(start) || literals.matchesVisibleLine(lead, logQuotationLead)) return null
        return separator until text.length
    }

    private fun loggedDispatchQuoteRanges(
        text: String, literals: ReplyLiterals, continuations: Map<String, Set<List<String>>>,
    ): List<IntRange> = buildList {
        for ((heading, variants) in continuations) {
            var offset = text.indexOf(heading)
            while (offset >= 0) {
                val start = lineStart(text, offset)
                val headingEnd = lineEnd(text, offset)
                offset = text.indexOf(heading, offset + heading.length)
                if (headingText(text.substring(start, headingEnd)) != heading ||
                    !literals.matchesVisibleLine(previousVisibleLine(literals.markdown, start), logQuotationLead)
                ) continue
                val end = matchedDispatchContinuationEnd(
                    text, headingEnd, variants, provisional = false, requireCompleteBlock = true,
                ) { literals.ranges.containsOffset(it) }
                if (end > headingEnd) add(start until end)
            }
        }
    }

    private fun interiorRepairEchoRanges(text: String, dispatch: RepairDispatch, provisional: Boolean): List<IntRange> {
        val (heading, continuations) = dispatch.continuations.entries.singleOrNull() ?: return emptyList()
        var occurrence = text.indexOf(heading)
        if (occurrence < 0) return emptyList()
        val literals = classifyLiterals(text, includeUnclosed = provisional)
        return buildList {
            while (occurrence >= 0) {
                val start = lineStart(text, occurrence)
                val end = lineEnd(text, occurrence)
                occurrence = text.indexOf(heading, occurrence + heading.length)
                if (headingText(text.substring(start, end)) != heading ||
                    !literals.isUnprotected(start) || hasOrphanClosingCaption(text, start)
                ) continue
                val before = previousVisibleLine(literals.markdown, start) ?: continue
                if (literals.matchesVisibleLine(before, logQuotationLead) ||
                    literals.matchesVisibleLine(before, metadataExampleBridge)
                ) continue
                val blockEnd = matchedDispatchContinuationEnd(text, end, continuations, provisional = false) {
                    !literals.isUnprotected(it)
                }
                if (blockEnd == end || blockEnd == text.length) continue
                val afterBlock = blockEnd + if (text.startsWith("\r\n", blockEnd)) 2 else 1
                val after = nextNonblankLine(literals.markdown, blockEnd) ?: continue
                if (literals.matchesVisibleLine(after, logQuotationTail) ||
                    literals.matchesVisibleLine(after, historyExampleExplanation)
                ) continue
                add(start until afterBlock)
            }
        }
    }

    private fun DispatchReply.withoutRanges(removals: List<IntRange>): DispatchReply {
        if (removals.isEmpty()) return this
        val originalSeparators = validatedReplyPartSeparators(text, partLengths) ?: return this
        val cuts = mutableListOf<IntRange>()
        for (range in removals.sortedBy { it.first }) {
            val prior = cuts.lastOrNull()
            if (prior != null && range.first <= prior.last + 1) {
                cuts[cuts.lastIndex] = prior.first..maxOf(prior.last, range.last)
            } else cuts += range
        }
        fun mappedOffset(offset: Int): Int = offset - cuts.sumOf { if (it.last < offset) it.last - it.first + 1 else 0 }
        val body = buildString {
            var copiedThrough = 0
            for (cut in cuts) {
                append(text, copiedThrough, cut.first)
                copiedThrough = cut.last + 1
            }
            append(text, copiedThrough, text.length)
        }
        val separators = originalSeparators.toMutableSet()
        for (cut in cuts) {
            // If an echo consumes the join between calls, use the surviving line break before
            // the echo as the join. Preserve that break's bytes, including a preceding CR.
            if (originalSeparators.any { it in cut } && text.getOrNull(cut.first - 1) == '\n') {
                separators += cut.first - 1
            }
        }
        val retainedSeparators = separators.filterNot { offset -> cuts.any { offset in it } }.sorted().map(::mappedOffset)
        val lengths = if (retainedSeparators.isEmpty()) emptyList() else {
            (listOf(-1) + retainedSeparators + body.length).zipWithNext { start, end -> end - start - 1 }
        }
        return DispatchReply(body, lengths, quotedBlocks.map { mappedOffset(it.first)..mappedOffset(it.last) })
    }

    private fun validatedReplyPartSeparators(text: String, lengths: List<Int>): List<Int>? {
        if (lengths.size <= 1) return emptyList()
        var offset = 0
        return buildList {
            for ((index, length) in lengths.withIndex()) {
                if (length < 0 || length > text.length - offset) return null
                offset += length
                if (index == lengths.lastIndex) {
                    if (offset != text.length) return null
                } else {
                    if (text.getOrNull(offset) != '\n') return null
                    add(offset++)
                }
            }
        }
    }

    private fun hasSeparateStoryAfterProjection(text: String, projectionEnd: Int): Boolean {
        val separator = paragraphBreak.matchAt(text, projectionEnd) ?: return false
        val start = separator.range.last + 1
        val storyStart = start + text.substring(start).takeWhile { it == ' ' }.length
        if (storyStart - start > 3 || text.getOrNull(storyStart)?.isLetterOrDigit() != true) return false
        val firstLine = text.substring(start, lineEnd(text, start))
        if (historyExampleExplanation.matches(firstLine) || historyElementAttributeExplanation.matches(firstLine) ||
            metadataExampleBridge.matches(firstLine) || logQuotationTail.matches(firstLine)
        ) return false
        return wrappers.none { wrapper ->
            text.indexOf(wrapper.opening, start) >= 0 || wrapper.closings.any { text.indexOf(it, start) >= 0 }
        }
    }

    private fun withoutHeadingBeforeTrailer(
        text: String, headings: List<String>, provisional: Boolean, formatOutput: FormatOutput,
        continuations: Map<String, Set<List<String>>>,
    ): String {
        if (headings.none { it in text }) return text
        val literals = classifyLiterals(text, includeUnclosed = provisional)
        val heading = headingBeforeTrailer(
            text, headings, provisional, formatOutput, literals, literals.replySuffixRanges(formatOutput, provisional),
            continuations,
        ) ?: return text
        return text.substring(0, heading.first).trimEnd('\r', '\n') + text.substring(heading.last + 1)
    }

    private fun headingBeforeTrailer(
        text: String,
        headings: List<String>,
        provisional: Boolean,
        formatOutput: FormatOutput,
        literals: ReplyLiterals,
        suffixRanges: List<IntRange>,
        continuations: Map<String, Set<List<String>>>,
        isJoinedLiteral: (Int) -> Boolean = { false },
    ): IntRange? {
        val loggedBlocks = loggedDispatchQuoteRanges(text, literals, continuations)
        var cursor = text.length
        var removeFrom = -1
        var preserveFrom = -1
        while (cursor > 0) {
            val end = (cursor - 1 downTo 0).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: break
            val start = lineStart(text, end)
            cursor = start
            val heading = headingText(text.substring(start, end))
            if (heading !in headings || !literals.isUnprotected(start) ||
                isJoinedLiteral(start) || loggedBlocks.any { start in it } || literals.followsLiteralHeadingLead(start)
            ) {
                if (removeFrom >= 0) break
                continue
            }
            if (removeFrom < 0) {
                val headingEnd = lineEnd(text, end)
                val blockEnd = continuations[heading]?.let {
                    matchedDispatchContinuationEnd(text, headingEnd, it, provisional = false) { position ->
                        literals.ranges.containsOffset(position) || isJoinedLiteral(position)
                    }
                } ?: headingEnd
                val lineBreak = text.indexOfAny(charArrayOf('\r', '\n'), blockEnd)
                if (lineBreak < 0 || !hasOnlyReplySuffix(
                        text, lineBreak, suffixRanges, literals, provisional, formatOutput, requireTrailer = true,
                    )
                ) continue
                preserveFrom = lineBreak
            }
            removeFrom = start
        }
        return if (removeFrom < 0) null else removeFrom until preserveFrom
    }

    private fun declaredUserOutput(text: String, requireExplicitCopy: Boolean = false): UserOutput {
        if (wrappers.none { it.opening in text } && wrapperClosings.none { it in text }) return UserOutput()
        val templateOpenings = mutableSetOf<String>()
        val counterexampleClosingStarts = mutableSetOf<Int>()
        val counterexampleOpenings = mutableSetOf<String>()
        val standaloneOpenings = mutableSetOf<String>()
        val standaloneClosings = mutableSetOf<String>()
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(text) }
        val exactBoundaries = literals.declaredBoundaryOpenings(text, wrappers)
        templateOpenings += exactBoundaries.persistent
        fun startsLine(offset: Int): Boolean {
            val start = lineStart(text, offset)
            return offset - start <= 3 && (start until offset).all { text[it] == ' ' }
        }
        val pairs = buildSet {
            for (family in wrapperFamilyTokens.values) {
                val tags = family.findAll(text).toList()
                for ((index, tag) in tags.withIndex()) {
                    val start = tag.range.first
                    val line = lineStart(text, start)
                    val atLineStart = startsLine(start)
                    val quotedOpening = tag.value !in wrapperClosings && start > line &&
                        MarkdownCodeFences.afterQuotePrefixes(literals.markdown, line) == start
                    val requestedInlineCopy = !atLineStart && !quotedOpening && tag.value !in wrapperClosings &&
                        inlineExactCopyInstruction.matches(text.subSequence(line, start))
                    if (!atLineStart && !quotedOpening && !requestedInlineCopy) continue
                    val lead = previousVisibleLine(literals.markdown, line)
                    if (tag.value in wrapperClosings) {
                        val after = tag.range.last + 1
                        if (literals.isUnprotected(start) && text.subSequence(after, text.length).lineSequence().first().isBlank()) {
                            val explicitRequest = lead != null && literals.matchesVisibleLine(lead, standaloneClosingInstruction) &&
                                literals.markdown.substring(lead.last + 1, line) in setOf("\n", "\r\n")
                            val beforeLead = lead?.takeIf { explicitRequest }?.let { previousVisibleLine(literals.markdown, it.first) }
                            if (literals.prohibitsWrapper(lead, after) ||
                                beforeLead?.let { literals.prohibitsWrapper(it, after) } == true
                            ) counterexampleClosingStarts += start
                            else if (explicitRequest) standaloneClosings += tag.value
                        }
                        continue
                    }
                    val wrapper = wrappers.firstOrNull { it.opening == tag.value } ?: continue
                    if (atLineStart && lead != null &&
                        literals.matchesVisibleLine(lead, standaloneOpeningInstruction) &&
                        literals.markdown.substring(lead.last + 1, line) in setOf("\n", "\r\n") &&
                        literals.isUnprotected(start)
                    ) {
                        val after = tag.range.last + 1
                        val tail = text.subSequence(after, text.length).lineSequence().first()
                        if (tail.all { it.isHorizontalPadding() } &&
                            !literals.prohibitsWrapper(previousVisibleLine(literals.markdown, lead.first), after)
                        ) standaloneOpenings += wrapper.opening
                    }
                    val visibleLead = literals.visibleLine(lead)
                    val requestedMultilineCopy = atLineStart && lead != null &&
                        visibleLead?.let(inlineExactCopyInstruction::matches) == true &&
                        literals.markdown.substring(lead.last + 1, line) in setOf("\n", "\r\n") &&
                        text.getOrNull(tag.range.last + 1).isHistoryLineBreak() && literals.isUnprotected(start)
                    val followingTags = tags.asSequence().drop(index + 1)
                    val closing = wrapperBoundary(wrapper, if (requestedMultilineCopy ||
                        requestedInlineCopy && literals.isUnprotected(start)
                    ) {
                        followingTags.filter { literals.isVisibleWrapperTag(text, it) }
                    } else followingTags).closing ?: continue
                    val end = closing.range.last + 1
                    val closingLineEnd = text.indexOfAny(charArrayOf('\r', '\n'), end).takeIf { it >= 0 } ?: text.length
                    if ((end until closingLineEnd).any { !text[it].isHorizontalPadding() }) continue
                    if (quotedOpening) {
                        if (lead == null || !literals.matchesVisibleLine(lead, inlineExactCopyInstruction) ||
                            literals.ranges.containsOffset(start) || literals.ranges.containsOffset(closing.range.first)
                        ) continue
                        val copy = exactQuotedPair(text, literals, lead, start, closing) ?: continue
                        if (literals.prohibitsWrapper(previousVisibleLine(literals.markdown, lead.first), end)) {
                            counterexampleOpenings += wrapper.opening
                            counterexampleClosingStarts += closing.range.first
                        } else add(copy)
                        continue
                    }
                    val visiblePair = literals.isUnprotected(start) && literals.isUnprotected(closing.range.first)
                    val singleLine = lineStart(text, closing.range.first) == line
                    val standalonePair = atLineStart && text.getOrNull(tag.range.last + 1).isHistoryLineBreak() &&
                        startsLine(closing.range.first)
                    val template = atLineStart && visiblePair && (standalonePair || singleLine) &&
                        literals.declaresSlotTemplate(lead, text.substring(tag.range.last + 1, closing.range.first).trim())
                    val labeled = visibleLead?.let(formatTemplateLabel::matches) == true
                    val prohibitionLead = if ((labeled || template || requestedMultilineCopy) && lead != null) {
                        previousVisibleLine(literals.markdown, lead.first)
                    } else lead
                    val explicitCopy = visiblePair && (requestedInlineCopy || requestedMultilineCopy)
                    if (!atLineStart && !explicitCopy) continue
                    if (visiblePair && literals.prohibitsWrapper(prohibitionLead, end)) {
                        counterexampleOpenings += wrapper.opening
                        counterexampleClosingStarts += closing.range.first
                        continue
                    }
                    val pair = text.substring(start, end)
                    // A slot establishes a format for new text. Only a literal copy reserves these exact bytes.
                    if (template) templateOpenings += wrapper.opening
                    else if (explicitCopy || !requireExplicitCopy && (singleLine || standalonePair)) add(pair)
                }
            }
        }
        for (fence in literals.fences) {
            val closingLine = fence.closingLineStart ?: continue
            if (!fence.topLevel || fence.info.lowercase() !in formatTemplateFenceInfo) continue
            val lead = previousVisibleLine(literals.markdown, fence.range.first) ?: continue
            val slot = literals.visibleLine(lead)?.let(::userTemplateSlotFromInstruction) ?: continue
            val previous = previousVisibleLine(literals.markdown, lead.first)
            if (literals.prohibitsWrapper(previous, fence.range.last + 1)) continue
            val contentStart = literals.markdown.indexOf('\n', fence.range.first) + 1
            val template = text.substring(contentStart, closingLine).trim()
            val wrapper = wrappers.firstOrNull { it.isStandalonePair(template) } ?: continue
            val close = wrapper.closings.first(template::endsWith)
            if (template.substring(wrapper.opening.length, template.length - close.length).trim() == slot) {
                templateOpenings += wrapper.opening
            }
        }
        for (sample in quotedTemplateSample.findAll(bareCarriageReturn.replace(text, "\n"))) {
            val opening = requireNotNull(sample.groups[1])
            val slot = requireNotNull(sample.groups[2])
            val closing = requireNotNull(sample.groups[3])
            val wrapper = wrappers.first { it.opening == opening.value }
            if (closing.value !in wrapper.closings ||
                listOf(opening, slot, closing).any { part -> literals.ranges.containsOffset(part.range.first) }
            ) continue
            val start = sample.range.first
            val end = sample.range.last + 1
            val nextLine = literals.markdown.indexOf('\n', end) + 1
            if (nextLine > 0 && (quoteSampleLine.matchAt(literals.markdown, nextLine) != null ||
                    literals.isLazyQuoteLine(nextLine))
            ) continue
            val lead = previousVisibleLine(literals.markdown, start) ?: continue
            val gap = literals.markdown.substring(lead.last + 1, start)
            if (gap != "\n" && gap != "\r\n") continue
            if (literals.visibleLine(lead)?.let(::userTemplateSlotFromInstruction) != slot.value) continue
            val previous = previousVisibleLine(literals.markdown, lead.first)
            if (literals.prohibitsWrapper(previous, end)) {
                counterexampleOpenings += wrapper.opening
                counterexampleClosingStarts += closing.range.first
            } else templateOpenings += wrapper.opening
        }
        return UserOutput(
            LiteralOutput(pairs, standaloneOpenings, standaloneClosings), templateOpenings,
            counterexampleClosingStarts, counterexampleOpenings, exactBoundaries.currentReply,
        )
    }

    /** An explicit copy directive applies to a whole quoted pair with explicit line prefixes. */
    private fun exactQuotedPair(
        text: String, literals: ReplyLiterals, lead: IntRange, opening: Int, closing: MatchResult,
    ): String? {
        val markdown = literals.markdown
        val firstLine = lineStart(markdown, opening)
        val lastLine = lineStart(markdown, closing.range.first)
        val gap = markdown.substring(lead.last + 1, firstLine)
        if (gap != "\n" && gap != "\r\n") return null
        val quoteDepth = markdown.subSequence(firstLine, opening).count { it == '>' }
        val afterClosing = closing.range.last + 1
        val endOfLastLine = nextHistoryLineStart(text, lineEnd(text, afterClosing))
        if (endOfLastLine < text.length && (MarkdownCodeFences.afterQuotePrefixes(markdown, endOfLastLine) > endOfLastLine ||
                literals.isLazyQuoteLine(endOfLastLine))) return null

        return buildString {
            var line = firstLine
            while (line <= lastLine) {
                val start = MarkdownCodeFences.afterQuotePrefixes(markdown, line)
                if (markdown.subSequence(line, start).count { it == '>' } != quoteDepth) return null
                if (line == lastLine) {
                    append(text, start, afterClosing)
                    break
                }
                val next = nextHistoryLineStart(text, lineEnd(text, start))
                append(text, start, next)
                line = next
            }
        }
    }

    private fun nextHistoryLineStart(text: String, end: Int): Int = when {
        end == text.length -> end
        text.startsWith("\r\n", end) -> end + 2
        else -> end + 1
    }

    private fun userTemplateSlotFromInstruction(line: String): String? {
        val match = userTemplateInstruction.matchEntire(line) ?: return null
        return (match.groups[1] ?: match.groups[2])?.value
    }

    private fun ReplyLiterals.declaresSlotTemplate(lead: IntRange?, slot: String): Boolean {
        if (!formatTemplateSlot.matches(slot)) return false
        val directive = visibleLine(lead) ?: return false
        val namedSlot = userTemplateSlotFromInstruction(directive)
        if (namedSlot != null) return slot == namedSlot
        return formatTemplateLabel.matches(directive) || templateUseAndTagRetention.matches(directive)
    }

    private fun currentUserOmitsXml(source: String): Boolean {
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(source) }
        return noXmlForCurrentReply.findAll(source).any { match ->
            val clause = requireNotNull(match.groups[1]).range
            literals.isUnprotected(clause.first) && literals.isUnprotected(clause.last)
        }
    }

    private fun declaredReplyHeadings(source: String): Set<String> {
        if ("【" !in source) return emptySet()
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(source) }
        return buildSet {
            for (heading in bracketedHeading.findAll(source)) {
                val titleStart = heading.range.first
                val start = lineStart(source, titleStart)
                val end = heading.range.last + 1
                if (inlineVerbatimHeadingLead.matches(source.substring(start, titleStart)) &&
                    headingSentenceEnd.matches(source.substring(end, lineEnd(source, end))) &&
                    literals.isUnprotected(start) && literals.isUnprotected(titleStart)
                ) add(heading.value)
            }
            for (match in explicitReplyHeading.findAll(source)) {
                val directiveStart = match.range.first
                val directiveEnd = match.range.last + 1
                val titleStart = when {
                    source.startsWith("\r\n", directiveEnd) -> directiveEnd + 2
                    source.getOrNull(directiveEnd).isHistoryLineBreak() -> directiveEnd + 1
                    else -> continue
                }
                val line = source.substring(titleStart, lineEnd(source, titleStart))
                val heading = headingText(line)?.takeIf { it.startsWith("【") && it.endsWith("】") } ?: continue
                if (!literals.isUnprotected(directiveStart) || !literals.isUnprotected(titleStart)) continue
                val beforeDirective = previousVisibleLine(literals.markdown, directiveStart)
                if (literals.visibleLine(beforeDirective)?.let(headingProhibition::containsMatchIn) != true) {
                    add(heading)
                }
            }
        }
    }

    private fun closingEvidence(token: String, sources: Sequence<Pair<String, Set<Int>>>): ClosingEvidence {
        var witness: String? = null
        var witnessOffset = -1
        for ((source, prohibitedStarts) in sources) {
            var position = source.indexOf(token)
            while (position >= 0) {
                if (position !in prohibitedStarts) {
                    if (witness != null || token !in wrapperClosings) return ClosingEvidence.PROTECTED
                    witness = source
                    witnessOffset = position
                }
                position = source.indexOf(token, position + 1)
            }
        }
        val source = witness ?: return ClosingEvidence.NONE
        val wrapper = wrappers.first { token in it.closings }
        val tags = wrapperFamilyTokens.getValue(wrapper.name).findAll(source).toList()
        val tag = tags.firstOrNull { it.range.first == witnessOffset && it.value == token } ?: return ClosingEvidence.PROTECTED
        val literals = classifyLiterals(source, tags, wrapper.closings)
        return if (literals.isVisibleWrapperTag(source, tag, allowIndentedParagraphTag = true)) ClosingEvidence.PROTECTED
        else ClosingEvidence.ONE_LITERAL
    }

    internal fun forRequest(narrativeMode: ChatNarrativeMode?, formatCard: String?): RequestCleanup =
        if (narrativeMode == null) RequestCleanup(enabled = false, formatCard = formatCard)
        else RequestCleanup(
            enabled = true, formatOutput = formatCard?.let(::declaredOutput) ?: FormatOutput(), formatCard = formatCard,
        )

    internal fun forDispatchedRequest(
        narrativeMode: ChatNarrativeMode?,
        formatCard: String?,
        position: FormatPromptPosition,
        hasCurrentUser: Boolean,
    ): RequestCleanup = forRequest(
        narrativeMode,
        formatCard.takeIf { position.includesStart || position.includesEnd && hasCurrentUser },
    )

    internal fun forFormatRepair(
        formatCard: String?, original: String, dispatch: RepairDispatch = RepairDispatch(),
    ): RequestCleanup {
        val output = formatCard?.let(::declaredOutput) ?: FormatOutput()
        val candidates = wrappers.filter {
            it.opening in output.counterexampleOpenings && it.opening !in output.declaredOpenings
        }
        val explicitClosings = formatCard?.let(::explicitlyProhibitedStandaloneClosings).orEmpty()
        val candidateFamilies = candidates.mapTo(mutableSetOf(), Wrapper::name)
        val sameFamily = wrappers.filter { it.name in candidateFamilies || it.closings.any { close -> close in explicitClosings } }
        val declaredClosings = wrappers.filter { it.opening in output.declaredOpenings }.flatMap(Wrapper::closings)
        val delimiterTokens = sameFamily.flatMapTo(mutableSetOf()) { listOf(it.opening) + it.closings }
        // Delimiters inside a literal are independent of a newly emitted outer pair.
        // Keep the stricter source check for orphan closers, where there is no matching opener.
        val visibleDelimiters = if (delimiterTokens.none { it in original }) emptyList() else {
            val tags = wrapperFamilyTokens.values.flatMap { it.findAll(original).toList() }
            val literals = classifyLiterals(original, tags)
            tags.filter {
                it.value in delimiterTokens && literals.isVisibleWrapperTag(original, it, includeStandaloneLazyTags = true)
            }.map { it.value }
        }
        val authoredOpeningFamilies = sameFamily.filter { it.opening in visibleDelimiters }.mapTo(mutableSetOf(), Wrapper::name)
        val openings = candidates.filter { wrapper ->
            val visibleClosingCount = visibleDelimiters.count { it in wrapper.closings }
            wrapper.opening !in visibleDelimiters &&
                (visibleClosingCount == 0 || visibleClosingCount == 1 && wrapper.name !in authoredOpeningFamilies)
        }
        val protected = openings.associate { wrapper ->
            wrapper.opening to wrapper.closings.filterTo(mutableSetOf()) { it in visibleDelimiters }
        }.filterValues { it.isNotEmpty() }
        val standaloneClosings = explicitClosings.filterTo(mutableSetOf()) { close ->
            close !in declaredClosings && close !in visibleDelimiters &&
                sameFamily.none { close in it.closings && it.name in authoredOpeningFamilies }
        }
        return RequestCleanup(
            enabled = openings.isNotEmpty() || standaloneClosings.isNotEmpty() || dispatch.boundaries.isNotEmpty() ||
                dispatch.unlabelledIntroduction != null,
            formatOutput = output, repairOpenings = openings.mapTo(mutableSetOf(), Wrapper::opening),
            repairOriginalContent = original, repairProtectedClosings = protected, repairStandaloneClosings = standaloneClosings,
            repairDispatch = dispatch,
        )
    }

    private fun explicitlyProhibitedStandaloneClosings(card: String): Set<String> {
        if (wrapperClosings.none { it in card }) return emptySet()
        val tags = wrapperFamilyTokens.values.flatMap { it.findAll(card).toList() }
        val literals = classifyLiterals(card, tags)
        return tags.filter { tag ->
            val start = lineStart(literals.markdown, tag.range.first)
            val lead = previousVisibleLine(literals.markdown, start)
            tag.value in wrapperClosings && literals.isStandaloneTagLine(tag.range.first, tag.value) &&
                literals.isVisibleWrapperTag(card, tag, includeStandaloneLazyTags = true) &&
                lead != null && literals.markdown.substring(lead.last + 1, start) in setOf("\n", "\r\n") &&
                literals.matchesVisibleLine(lead, standaloneClosingProhibition)
        }.mapTo(mutableSetOf()) { it.value }
    }

    private fun hasAuthoredHeading(source: String, token: String, issuedHeadings: Set<String>): Boolean {
        var position = source.indexOf(token)
        if (position < 0) return false
        val possibleHeadings = issuedHeadings.filter { it.startsWith(token) }
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(source) }
        while (position >= 0) {
            val heading = possibleHeadings.firstOrNull { source.startsWith(it, position) } ?: return true
            val start = lineStart(source, position)
            val end = position + heading.length
            val inline = source.substring(start, position).isNotBlank()
            val tail = source.substring(end, lineEnd(source, end))
            if (if (inline) !headingSentenceEnd.matches(tail) else tail.isNotBlank()) return true
            val lead = if (inline) start until position else {
                previousVisibleLine(source, start) ?: return true
            }
            val prohibition = if (inline) inlineHeadingProhibition else headingProhibition
            val instruction = prohibition.find(source.substring(lead))?.groups?.get(1) ?: return true
            if (!literals.isUnprotected(lead.first + instruction.range.first) || !literals.isUnprotected(position)) {
                return true
            }
            position = source.indexOf(token, end)
        }
        return false
    }

    private fun withoutEchoedHeading(
        text: String, headings: List<String>, provisional: Boolean, isAuthored: (String) -> Boolean,
        continuations: Map<String, Set<List<String>>>,
        isJoinedLiteral: (Int) -> Boolean = { false },
        allowPartialHeading: Boolean = true,
    ): HeadingSelection {
        if (headings.isEmpty() || text.isBlank()) return HeadingSelection(text.indices)
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(text, includeUnclosed = provisional) }
        fun matches(rawLine: String, allowPrefix: Boolean): Boolean {
            val line = headingText(rawLine) ?: return false
            return headings.any { heading ->
                line == heading || allowPartialHeading && allowPrefix && heading.startsWith(line) &&
                    line.startsWith(heading.substringBefore('（').removeSuffix("】")) && !isAuthored(line)
            }
        }

        val commentsEnd = leadingClosedCommentLinesEnd(text)
        var bodyStart = commentsEnd
        var cursor = commentsEnd
        while (cursor < text.length) {
            val start = (cursor until text.length).firstOrNull { !text[it].isHistoryLineBreak() } ?: break
            val end = text.indexOfAny(charArrayOf('\r', '\n'), start).takeIf { it >= 0 } ?: text.length
            cursor = (end until text.length).firstOrNull { !text[it].isHistoryLineBreak() } ?: text.length
            val line = text.substring(start, end)
            if (line.all { it.isHorizontalPadding() }) continue
            if (!matches(line, provisional && end == text.length) ||
                !literals.isUnprotected(start) || isJoinedLiteral(start)
            ) break
            val blockContinuations = continuations[headingText(line)]
            if (blockContinuations != null) {
                val blockEnd = matchedDispatchContinuationEnd(text, end, blockContinuations, provisional) {
                    literals.ranges.containsOffset(it) || isJoinedLiteral(it)
                }
                cursor = (blockEnd until text.length).firstOrNull { !text[it].isHistoryLineBreak() } ?: text.length
            }
            bodyStart = cursor
        }
        val prefixEnd = if (bodyStart > commentsEnd) commentsEnd else 0
        if (bodyStart == commentsEnd) bodyStart = 0
        var bodyEnd = text.length
        while (bodyEnd > bodyStart) {
            val end = (bodyEnd - 1 downTo bodyStart).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: break
            val start = lineStart(text, end)
            val blockStart = lastExactDispatchBlockStart(text, bodyStart, end, headings, continuations, provisional) { at, heading ->
                isJoinedLiteral(at) || if (heading) {
                    !literals.isUnprotected(at) || literals.followsLiteralHeadingLead(at)
                } else literals.ranges.containsOffset(at)
            }
            if (blockStart != null) {
                bodyEnd = (blockStart - 1 downTo bodyStart).firstOrNull { !text[it].isHistoryLineBreak() }?.plus(1) ?: bodyStart
                continue
            }
            if (!matches(text.substring(start, end), provisional && bodyEnd == text.length) ||
                !literals.isUnprotected(start) || isJoinedLiteral(start) || literals.followsLiteralHeadingLead(start)
            ) break
            bodyEnd = (start - 1 downTo bodyStart).firstOrNull { !text[it].isHistoryLineBreak() }?.plus(1) ?: bodyStart
        }
        return HeadingSelection(bodyStart until bodyEnd, prefixEnd)
    }

    private fun lastExactDispatchBlockStart(
        text: String, minStart: Int, end: Int, headings: List<String>,
        continuations: Map<String, Set<List<String>>>, provisional: Boolean,
        isProtected: (Int, Boolean) -> Boolean,
    ): Int? {
        var best: Int? = null
        for ((heading, variants) in continuations) {
            if (heading !in headings) continue
            var occurrence = text.lastIndexOf(heading, end - heading.length)
            while (occurrence >= minStart) {
                val start = lineStart(text, occurrence)
                val headingEnd = lineEnd(text, occurrence)
                if (start >= minStart && headingEnd < end && headingText(text.substring(start, headingEnd)) == heading &&
                    !isProtected(start, true)
                ) {
                    val blockEnd = matchedDispatchContinuationEnd(text, headingEnd, variants, provisional) {
                        isProtected(it, false)
                    }
                    if (blockEnd > headingEnd && blockEnd <= end && text.subSequence(blockEnd, end).isBlank()) {
                        best = minOf(best ?: start, start)
                    }
                }
                occurrence = text.lastIndexOf(heading, occurrence - 1)
            }
        }
        return best
    }

    /** Continue a previously matched literal application heading through the exact dispatched physical lines. */
    private fun matchedDispatchContinuationEnd(
        text: String, headingEnd: Int, variants: Set<List<String>>, provisional: Boolean,
        requireCompleteBlock: Boolean = false, onUnfinishedShortLine: ((Int) -> Unit)? = null,
        isProtected: (Int) -> Boolean,
    ): Int {
        var end = headingEnd
        var matchedEnd = headingEnd
        var candidates: Collection<List<String>> = variants
        var depth = 0
        var matchedInstruction = false
        while (end < text.length) {
            val start = end + if (text.startsWith("\r\n", end)) 2 else 1
            if (start == text.length || isProtected(start)) break
            end = lineEnd(text, start)
            val line = text.substring(start, end)
            val exact = candidates.filter { it.getOrNull(depth) == line }
            if (exact.isEmpty()) {
                // Hold a substantial unfinished line only after its issued heading and other anchors agree.
                val uniqueNext = candidates.mapNotNull { it.getOrNull(depth) }.distinct().singleOrNull()
                if (provisional && end == text.length && line.isNotBlank() && uniqueNext?.startsWith(line) == true &&
                    line.trim().length < 8 && matchedInstruction
                ) onUnfinishedShortLine?.invoke(start)
                if (!requireCompleteBlock && provisional && end == text.length && line.trim().length >= 8 &&
                    uniqueNext?.startsWith(line) == true
                ) return end
                break
            }
            if (line.isNotBlank()) {
                matchedInstruction = true
                if (!requireCompleteBlock || exact.any { it.size == depth + 1 }) matchedEnd = end
            }
            candidates = exact
            depth++
        }
        return matchedEnd
    }

    private fun leadingClosedCommentLinesEnd(text: String): Int {
        var offset = 0
        while (offset < text.length) {
            val opening = (offset until text.length).firstOrNull { !text[it].isWhitespace() } ?: break
            if (!text.startsWith(commentOpening, opening) || hasCodeIndent(text, opening)) break
            val end = closedCommentEnd(text, opening) ?: break
            val lineBreak = text.indexOfAny(charArrayOf('\r', '\n'), end)
            if (lineBreak < 0 || text.subSequence(end, lineBreak).isNotBlank()) break
            offset = if (text.startsWith("\r\n", lineBreak)) lineBreak + 2 else lineBreak + 1
        }
        return offset
    }

    private fun headingText(line: String): String? {
        val start = line.indexOfFirst { it != ' ' }
        return if (start in 0..3) line.substring(start).trimEnd(' ', '\t') else null
    }

    private fun ReplyLiterals.followsLiteralHeadingLead(start: Int): Boolean =
        visibleLine(previousVisibleLine(markdown, start))?.let(literalHeadingLead::containsMatchIn) == true

    private fun List<Int>.trimTo(reply: IntRange, textLength: Int): List<Int> {
        if (size <= 1 || any { it < 0 } || sumOf(Int::toLong) + size - 1 != textLength.toLong()) return emptyList()
        var offset = 0
        val end = reply.last + 1
        return mapNotNull { length ->
            val limit = offset + length
            val overlap = minOf(limit, end) - maxOf(offset, reply.first)
            offset = limit + 1
            overlap.takeIf { it >= 0 }
        }
    }

    private fun withoutOrphanClosing(
        text: String, closings: List<String>, provisional: Boolean, formatOutput: FormatOutput,
        authored: (String) -> ClosingEvidence,
        isJoinedLiteral: (Int) -> Boolean = { false },
    ): String {
        if (closings.isEmpty() || "</" !in text) return text
        val end = text.trimEnd()
        val partialStart = lineStart(end, end.length)
        val partial = if (provisional) end.substring(partialStart).trimStart(' ') else ""
        val tags = wrappers.filter { wrapper -> wrapper.closings.any { it in closings } }
            .distinctBy(Wrapper::name)
            .flatMap { wrapperFamilyTokens.getValue(it.name).findAll(end).toList() }
            .sortedBy { it.range.first }
        val candidates = tags.filter { it.value in closings && hasMarkerPadding(end, it.range.first) }
        val partialClosing = partial.takeIf {
            it.length > 2 && hasMarkerPadding(end, end.length - it.length) && closings.any { close ->
                val name = wrappers.first { close in it.closings }.name
                isIdentifiableClosingPrefix(it, name) && it.length < close.length && close.startsWith(it)
            } && authored(it) == ClosingEvidence.NONE
        }
        if (candidates.isEmpty() && partialClosing == null) return text
        val literals = classifyLiterals(end, tags, closings, includeUnclosed = provisional)
        val visible = tags.filter {
            literals.isVisibleWrapperTag(end, it, includeStandaloneLazyTags = true) &&
                !literals.isLazyListItemLine(it.range.first) && !isJoinedLiteral(it.range.first)
        }
        val close = visible.singleOrNull()?.takeIf { it in candidates }
        if (close != null) when (authored(close.value)) {
            ClosingEvidence.NONE -> Unit
            ClosingEvidence.PROTECTED -> return text
            ClosingEvidence.ONE_LITERAL -> {
                val occurrences = tags.filter { it.value == close.value }
                if (occurrences.size != 2 || occurrences.first().range.last >= close.range.first ||
                    literals.isVisibleWrapperTag(end, occurrences.first(), allowIndentedParagraphTag = true)
                ) return text
            }
        }
        val position = close?.range?.first ?: if (visible.isEmpty() && partialClosing != null) {
            (end.length - partialClosing.length).takeIf {
                literals.isUnprotected(it) && !literals.isLazyListItemLine(it) && !isJoinedLiteral(it)
            }
                ?: return text
        } else return text
        if (hasOrphanClosingCaption(end, position)) return text
        val before = end.substring(0, lineStart(end, position))
        if (close == null || close.range.last == end.lastIndex) return before.trimEnd('\r', '\n')
        val suffixStart = close.range.last + 1
        if (!hasOnlyReplySuffix(
                end, suffixStart, literals.replySuffixRanges(formatOutput, provisional), literals,
                provisional, formatOutput,
            )
        ) return text
        val suffix = text.substring(suffixStart)
        return if (suffix.lineSequence().first().isNotBlank()) before + suffix else before.trimEnd('\r', '\n') + suffix
    }

    private fun isIdentifiableClosingPrefix(prefix: String, wrapperName: String): Boolean =
        prefix.startsWith("</${wrapperName.dropLast(1)}")

    private fun hasOrphanClosingCaption(text: String, position: Int, before: Int = position): Boolean {
        var previous = lineStart(text, position) - 1
        while (previous >= 0 && text[previous].isWhitespace()) previous--
        val character = text.getOrNull(previous)
        return previous < before && (character == ':' || character == '：')
    }

    private fun ReplyLiterals.declaredBoundaryOpenings(source: String, candidates: List<Wrapper>): BoundaryOpenings {
        val persistent = mutableSetOf<String>()
        val currentReply = mutableSetOf<String>()
        for (directive in exactBoundaryLines.findAll(source)) {
            val (opening, closing) = directive.destructured
            val wrapper = candidates.firstOrNull { it.opening == opening && closing in it.closings } ?: continue
            val start = directive.range.first
            if (!isUnprotected(start)) continue
            val before = previousVisibleLine(markdown, start)
            if (prohibitsWrapper(before, directive.range.last + 1) || isDocumentaryHistoryLead(before)) continue
            val scoped = before != null && matchesVisibleLine(before, singleReplyBoundaryScope) &&
                source.substring(before.last + 1, start) in setOf("\n", "\r\n")
            (if (scoped) currentReply else persistent).add(wrapper.opening)
        }
        return BoundaryOpenings(persistent, currentReply)
    }

    private fun declaredOutput(formatCard: String): FormatOutput {
        val candidates = wrappers.filter { it.opening in formatCard }
        if (candidates.isEmpty() && annotatedRoleplayActionOpening !in formatCard && "```" !in formatCard) {
            return FormatOutput()
        }
        val tokens = candidates.map { it.name }.distinct().flatMap { name ->
            wrapperFamilyTokens.getValue(name).findAll(formatCard).toList()
        }.sortedBy { it.range.first }
        val literals = classifyLiterals(formatCard, tokens)
        val markup = tokens.filter {
            literals.isVisibleWrapperTag(formatCard, it, includeStandaloneLazyTags = true)
        }
        val trailingStatusInfos = mutableSetOf<String>()
        val trailingBareStatusLeads = mutableSetOf<String>()
        val singlePairOpenings = mutableSetOf<String>()
        val counterexampleOpenings = mutableSetOf<String>()
        val counterexampleClosingStarts = mutableSetOf<Int>()
        val declaredOpenings = buildSet {
            val boundaries = literals.declaredBoundaryOpenings(formatCard, candidates)
            addAll(boundaries.persistent)
            addAll(boundaries.currentReply)
            var matchedEnd = -1
            for ((index, opening) in markup.withIndex()) {
                val start = opening.range.first
                if (start <= matchedEnd) continue
                val wrapper = candidates.firstOrNull { it.opening == opening.value } ?: continue
                val prefixStart = lineStart(formatCard, start)
                val prefix = formatCard.subSequence(prefixStart, start)
                val before = previousVisibleLine(literals.markdown, prefixStart)
                val standaloneLabel = before?.takeIf { line ->
                    formatTemplateLabel.matches(literals.markdown.substring(line)) &&
                        !literals.ranges.containsOffset(line.first) &&
                        !literals.isMarkdownContainerLine(line.first, includeStandaloneLazyTags = true) &&
                        (prefixStart until start).all { offset ->
                            formatCard[offset].isWhitespace() || literals.commentRanges.any { offset in it }
                        }
                }
                val explicitLabel = standaloneLabel != null ||
                    formatTemplateLabel.matches(literals.markdown.subSequence(prefixStart, start))
                if (!explicitLabel && !prefix.isBlank()) continue
                val family = wrapperFamilyTokens.getValue(wrapper.name)
                val closing = wrapperBoundary(
                    wrapper, markup.asSequence().drop(index + 1).filter { family.matches(it.value) },
                ).closing ?: continue
                matchedEnd = closing.range.last
                val suffix = literals.markdown.substring(matchedEnd + 1).lineSequence().first()
                if (!suffix.isBlank() && !(explicitLabel && formatTemplateAffirmation.matches(suffix))) continue

                val standaloneDirective = standaloneLabel ?: before?.takeIf {
                    prefix.isBlank() && literals.declaresSlotTemplate(
                        it, formatCard.substring(opening.range.last + 1, closing.range.first).trim(),
                    )
                }
                val lead = if (standaloneDirective == null) before else {
                    previousVisibleLine(literals.markdown, standaloneDirective.first)
                }
                if (literals.prohibitsWrapper(lead, matchedEnd + 1)) {
                    counterexampleOpenings += wrapper.opening
                    counterexampleClosingStarts += closing.range.first
                    continue
                }
                val hasOutputDirective = explicitLabel || standaloneDirective != null
                if (!hasOutputDirective && literals.isDocumentaryHistoryLead(lead)) continue
                add(wrapper.opening)
                if (hasOutputDirective && lead != null &&
                    singlePairTemplateConstraint.matches(literals.markdown.substring(lead)) &&
                    wrapper.hasExactlyOnePair(formatCard.substring(start, matchedEnd + 1))
                ) singlePairOpenings += wrapper.opening
            }
            for (fence in literals.fences) {
                val closingLine = fence.closingLineStart ?: continue
                if (!fence.topLevel) continue
                val label = previousVisibleLine(literals.markdown, fence.range.first) ?: continue
                if (literals.ranges.containsOffset(label.first) ||
                    literals.isMarkdownContainerLine(label.first, includeStandaloneLazyTags = true)
                ) continue
                val before = previousVisibleLine(literals.markdown, label.first)
                val after = nextNonblankLine(literals.markdown, fence.range.last + 1)
                val isCounterexample = literals.isCounterexampleBefore(before) ||
                    literals.isCounterexampleBefore(label) || literals.matchesVisibleLine(after, formatCounterexampleTail)

                val labelText = literals.markdown.substring(label)
                val precedingInstruction = literals.visibleLine(before)
                val isReplySample = replySampleLabel.matches(labelText)
                val contentStart = literals.markdown.indexOf('\n', fence.range.first) + 1
                val template = formatCard.substring(contentStart, closingLine).trim()
                if (isCounterexample) {
                    val example = candidates.firstOrNull {
                        template.startsWith(it.opening) && it.closings.any(template::endsWith)
                    }
                    if (example != null && cleanReply(
                            template, streaming = false, interrupted = false, requirePair = true,
                        ).matchedOuterPair
                    ) {
                        counterexampleOpenings += example.opening
                        val templateStart = formatCard.indexOf(example.opening, contentStart)
                        val close = example.closings.first(template::endsWith)
                        counterexampleClosingStarts += templateStart + template.length - close.length
                    }
                    continue
                }
                val statusInfo = fence.statusInfo()
                if (statusInfo != null && template.isNotBlank() &&
                    (trailingStatusLabel.matches(labelText) ||
                        isReplySample && precedingInstruction?.let(trailingStatusLabel::matches) == true)
                ) {
                    if (statusInfo.isEmpty()) {
                        val lead = statusLead(template.lineSequence().first())
                        if (lead.isNotEmpty()) trailingBareStatusLeads += lead
                    } else {
                        trailingStatusInfos += statusInfo
                    }
                }
                val explicitReplySample = isReplySample && requiresUnfencedTags(precedingInstruction)
                val explicitOutputFormat = outputFormatLabel.matches(labelText) &&
                    (requiresUnfencedTags(precedingInstruction) || requiresUnfencedTags(literals.visibleLine(after)))
                if (fence.info.lowercase() !in formatTemplateFenceInfo) continue
                val labeled = fencedTemplateLabel.matches(labelText) || formatTemplateLabel.matches(labelText) ||
                    explicitReplySample || explicitOutputFormat
                val wrapper = candidates.firstOrNull {
                    template.startsWith(it.opening) && it.closings.any(template::endsWith)
                } ?: continue
                if (!labeled && (!wrapper.hasTemplateSlot(template) || !literals.directlyRequiresUnfencedTags(label, before))) {
                    continue
                }
                if (cleanReply(template, streaming = false, interrupted = false, requirePair = true).matchedOuterPair) {
                    add(wrapper.opening)
                    if (before != null && singlePairTemplateConstraint.matches(literals.markdown.substring(before)) &&
                        wrapper.hasExactlyOnePair(template)
                    ) singlePairOpenings += wrapper.opening
                }
            }
        }
        val declaresAnnotatedActions = roleplayAnnotatedActionRanges(literals.markdown).any { range ->
            literals.isUnprotected(range.first) && literals.isUnprotected(range.last) &&
                !literals.matchesVisibleLine(
                    previousVisibleLine(literals.markdown, range.first), formatCounterexampleLead,
                ) &&
                !literals.matchesVisibleLine(nextNonblankLine(literals.markdown, range.last + 1), formatCounterexampleTail)
        }
        return FormatOutput(
            declaredOpenings, trailingStatusInfos, trailingBareStatusLeads, singlePairOpenings, declaresAnnotatedActions,
            counterexampleOpenings, counterexampleClosingStarts,
        )
    }

    private fun requiresUnfencedTags(instruction: String?): Boolean =
        instruction != null && keepSampleTags.containsMatchIn(instruction) && omitSampleFence.containsMatchIn(instruction)

    private fun ReplyLiterals.directlyRequiresUnfencedTags(line: IntRange, previous: IntRange?): Boolean {
        val adjacent = previous?.takeIf {
            markdown.subSequence(it.last + 1, line.first).count { char -> char == '\n' } == 1
        }
        val lines = listOfNotNull(adjacent, line)
        fun hasVisibleCommand(pattern: Regex): Boolean = lines.any { range ->
            val command = visibleLine(range)?.trimEnd()?.let(pattern::find)?.groups?.get(1)
            command != null && isUnprotected(range.first + command.range.first) &&
                isUnprotected(range.first + command.range.last)
        }
        return hasVisibleCommand(directTemplateKeep) && hasVisibleCommand(omitSampleFence)
    }

    private fun Wrapper.hasTemplateSlot(template: String): Boolean {
        if (!isStandalonePair(template)) return false
        val close = closings.first(template::endsWith)
        return formatTemplateSlot.matches(template.substring(opening.length, template.length - close.length).trim())
    }

    private fun ReplyLiterals.visibleLine(line: IntRange?): String? =
        line?.takeIf { isUnprotected(it.first) }?.let(markdown::substring)

    private fun ReplyLiterals.matchesVisibleLine(line: IntRange?, pattern: Regex): Boolean =
        visibleLine(line)?.let(pattern::matches) == true

    private fun ReplyLiterals.isDocumentaryHistoryLead(lead: IntRange?): Boolean {
        if (lead == null) return false
        val instruction = visibleLine(lead) ?: return false
        return listOf(referenceOnly, historyReference, deniesReproducingSample).all { evidence ->
            evidence.findAll(instruction).any { match ->
                isUnprotected(lead.first + match.range.first) && isUnprotected(lead.first + match.range.last)
            }
        }
    }

    private fun ReplyLiterals.isCounterexampleBefore(lead: IntRange?): Boolean {
        if (lead == null) return false
        val instruction = visibleLine(lead) ?: return false
        if (formatCounterexampleLead.matches(instruction)) return true
        return metadataExampleBridge.matches(instruction) &&
            matchesVisibleLine(previousVisibleLine(markdown, lead.first), formatCounterexampleLead)
    }

    private fun ReplyLiterals.prohibitsWrapper(lead: IntRange?, after: Int): Boolean =
        isCounterexampleBefore(lead) ||
            matchesVisibleLine(nextNonblankLine(markdown, after), formatCounterexampleTail)

    private fun ReplyLiterals.isUnprotected(position: Int): Boolean =
        !ranges.containsOffset(position) && !isMarkdownContainerLine(position, includeStandaloneLazyTags = true)

    private fun Wrapper.hasExactlyOnePair(text: String): Boolean =
        text.startsWith(opening) && closings.any(text::endsWith) &&
            wrapperFamilyTokens.getValue(name).findAll(text).count() == 2

    private fun Wrapper.hasExactlyOneVisiblePair(text: String): Boolean {
        val tags = wrapperFamilyTokens.getValue(name).findAll(text).toList()
        val literals = classifyLiterals(text, tags, closings)
        val visible = tags.filter { literals.isVisibleWrapperTag(text, it) }
        return visible.size == 2 && visible.first().range.first == 0 &&
            visible.last().range.last + 1 == text.length && visible.last().value in closings
    }

    private fun Wrapper.isStandalonePair(text: String): Boolean =
        text.startsWith(opening) && text.getOrNull(opening.length).isHistoryLineBreak() &&
            closings.any { text.endsWith(it) && text.getOrNull(text.length - it.length - 1).isHistoryLineBreak() }

    private fun MarkdownCodeFences.Block.statusInfo(): String? =
        info.lowercase().takeIf { topLevel && marker == '`' && markerLength == 3 && (it.isEmpty() || it in typedStatusInfos) }

    private fun statusLead(line: String): String {
        val colon = line.indexOfFirst { it == ':' || it == '：' }
        return (if (colon >= 0) line.substring(0, colon) else line).trim().let {
            if (colon >= 0 && it.isNotEmpty()) "$it:" else it
        }
    }

    private fun MarkdownCodeFences.Block.matchesBareStatusLead(
        text: String, declaredLeads: Set<String>, provisional: Boolean,
    ): Boolean {
        if (declaredLeads.isEmpty() || closingLineStart == null && !provisional) return false
        val firstNewline = text.indexOf('\n', range.first)
        val bodyEnd = closingLineStart ?: range.last + 1
        val body = if (firstNewline in range) text.substring(firstNewline + 1, bodyEnd) else ""
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }
        if (firstLine == null) return closingLineStart == null && provisional
        val lead = statusLead(firstLine)
        return lead in declaredLeads || lead.isNotEmpty() && closingLineStart == null && provisional &&
            !body.endsWith('\n') && body.trimStart() == firstLine.trimStart() && declaredLeads.any { it.startsWith(lead) }
    }

    private fun previousVisibleLine(markdown: String, before: Int): IntRange? {
        var end = before
        while (end > 0) {
            end--
            if (markdown[end] == '\n' && end > 0 && markdown[end - 1] == '\r') end--
            val start = lineStart(markdown, end)
            if (markdown.subSequence(start, end).isNotBlank()) return start until end
            end = start
        }
        return null
    }

    private fun nextNonblankLine(markdown: String, after: Int): IntRange? {
        var start = markdown.indexOf('\n', after) + 1
        if (start == 0) return null
        while (start < markdown.length) {
            val newline = markdown.indexOf('\n', start).takeIf { it >= 0 } ?: markdown.length
            val end = if (markdown.getOrNull(newline - 1) == '\r') newline - 1 else newline
            if (markdown.subSequence(start, end).isNotBlank()) return start until end
            start = newline + 1
        }
        return null
    }

    fun completedBody(
        text: String,
        enabled: Boolean,
        interrupted: Boolean = false,
        partLengths: List<Int> = emptyList(),
        streaming: Boolean = false,
    ): String = if (enabled) {
        cleanCompletedBody(text, FormatOutput(), interrupted, partLengths, streaming)
    } else text

    private fun cleanCompletedBody(
        text: String,
        formatOutput: FormatOutput,
        interrupted: Boolean,
        partLengths: List<Int>,
        streaming: Boolean,
        projectedDuplicateOpenings: Set<String> = emptySet(),
        projectedLegacyInners: Set<String> = emptySet(),
        projectedBodies: Map<String, String> = emptyMap(),
        allowedOpenings: Set<String>? = null,
        repairProtectedClosings: Map<String, Set<String>> = emptyMap(),
        literalOutput: LiteralOutput = LiteralOutput(),
        trailerHeadings: List<String> = emptyList(),
        trailerContinuations: Map<String, Set<List<String>>> = emptyMap(),
        cleanExposedBody: (String) -> String = { it },
        cleanPartHeading: ((String, (Int) -> Boolean) -> HeadingSelection)? = null,
        protectCaptionedPartHeading: Boolean = false,
        isQuotedDispatchBlock: (Int) -> Boolean = { false },
        cleanPartOrphan: ((String, (Int) -> Boolean) -> String)? = null,
    ): String {
        fun clean(part: String, requirePair: Boolean = false) = cleanReply(
            part, streaming, interrupted, requirePair = requirePair, formatOutput = formatOutput,
            projectedDuplicateOpenings = projectedDuplicateOpenings,
            projectedLegacyInners = projectedLegacyInners, projectedBodies = projectedBodies,
            allowedOpenings = allowedOpenings, repairProtectedClosings = repairProtectedClosings,
            literalOutput = literalOutput, trailerHeadings = trailerHeadings,
            trailerContinuations = trailerContinuations,
            cleanExposedBody = cleanExposedBody,
        )
        if (partLengths.size > 1 && partLengths.sumOf(Int::toLong) + partLengths.size - 1 == text.length.toLong()) {
            var offset = 0
            val parts = partLengths.map { length ->
                val end = offset + length
                if (length < 0 || end !in offset..text.length ||
                    (end < text.length && text[end] != '\n')
                ) return clean(text).body
                (offset until end).also { offset = end + 1 }
            }
            // A model can spread one wrapper across several valid game_content calls.
            // A complete match on the joined body takes precedence over per-call cleanup,
            // including when the format card reserves the matching outer wrapper.
            val wholeReply = cleanReply(
                text, streaming, interrupted, requirePair = true, formatOutput = formatOutput,
                projectedDuplicateOpenings = projectedDuplicateOpenings,
                projectedLegacyInners = projectedLegacyInners, projectedBodies = projectedBodies,
                allowedOpenings = allowedOpenings, repairProtectedClosings = repairProtectedClosings,
                literalOutput = literalOutput, trailerHeadings = trailerHeadings,
                trailerContinuations = trailerContinuations,
                cleanExposedBody = cleanExposedBody,
            )
            if (wholeReply.matchedOuterPair) return wholeReply.body
            // Independent wrappers and groups of calls can coexist in the same tool reply.
            val joinedTags by lazy(LazyThreadSafetyMode.NONE) {
                wrapperFamilyTokens.mapValues { (_, family) -> family.findAll(text).toList() }
            }
            val joinedLiterals by lazy(LazyThreadSafetyMode.NONE) {
                val tags = joinedTags.values.flatten().sortedBy { it.range.first }
                classifyLiterals(text, tags, wrappers.flatMap { it.closings }, includeUnclosed = streaming || interrupted)
            }
            val toolCloses by lazy(LazyThreadSafetyMode.NONE) {
                toolClosesByOpening(text, joinedTags, joinedLiterals, literalOutput)
            }
            fun explainsPreviousExample(index: Int, afterFrame: Boolean = false): Boolean {
                val part = parts.getOrNull(index) ?: return false
                val first = text.substring(part).lineSequence().firstOrNull(String::isNotBlank)
                if (first?.let(historyExampleExplanation::matches) == true) return true
                if (!afterFrame) return false
                val line = nextNonblankLine(joinedLiterals.markdown, part.first - 1) ?: return false
                return line.last in part && joinedLiterals.matchesVisibleLine(line, historyElementAttributeExplanation)
            }

            fun isBoundedLogQuotation(first: Int, last: Int): Boolean {
                val before = previousVisibleLine(joinedLiterals.markdown, parts[first].first) ?: return false
                val after = nextNonblankLine(joinedLiterals.markdown, parts[last].last + 1) ?: return false
                fun separatesCaption(start: Int, end: Int): Boolean {
                    val whitespace = text.subSequence(start, end)
                    return whitespace == "\n" || whitespace == "\r\n" || paragraphBreak.matches(whitespace)
                }
                return separatesCaption(before.last + 1, parts[first].first) &&
                    separatesCaption(parts[last].last + 1, after.first) &&
                    joinedLiterals.matchesVisibleLine(before, logQuotationLead) && joinedLiterals.matchesVisibleLine(after, logQuotationTail)
            }

            var previousGroupClosedWrapper = false
            fun cleanParts(
                first: Int,
                last: Int,
                requirePair: Boolean = false,
                followsClosedWrapper: Boolean = previousGroupClosedWrapper,
            ): CleanedReply {
                val start = parts[first].first
                val part = text.substring(start, parts[last].last + 1)
                fun isJoinedLiteral(index: Int, opening: Boolean, afterClosedSection: Boolean = false): Boolean {
                    val joinedIndex = start + index
                    return joinedLiterals.ranges.containsOffset(joinedIndex) ||
                        joinedLiterals.isMarkdownContainerLine(
                            joinedIndex,
                            includeStandaloneLazyTags = opening && !followsClosedWrapper && !afterClosedSection,
                        ) || !opening && joinedLiterals.isCaptionedClosing(joinedIndex, literalOutput.closings)
                }
                fun cleanSection(
                    section: String, sectionStart: Int, requireSectionPair: Boolean, afterClosedSection: Boolean = false,
                ): CleanedReply =
                    cleanReply(
                        section, streaming, interrupted,
                        requirePair = requireSectionPair, formatOutput = formatOutput,
                        projectedDuplicateOpenings = projectedDuplicateOpenings,
                        projectedLegacyInners = projectedLegacyInners, projectedBodies = projectedBodies,
                        allowedOpenings = allowedOpenings,
                        repairProtectedClosings = repairProtectedClosings,
                        literalOutput = literalOutput, trailerHeadings = trailerHeadings,
                        trailerContinuations = trailerContinuations,
                        cleanExposedBody = cleanExposedBody,
                        isJoinedLiteral = { index, opening -> isJoinedLiteral(sectionStart + index, opening, afterClosedSection) },
                    )
                val cleaned = cleanSection(part, 0, requirePair)
                if (first == last && cleaned.couldSpanToolCalls) {
                    val adjacent = cleanAdjacentHistoryPairs(
                        part, allowedOpenings.orEmpty(), formatOutput.declaredOpenings, streaming || interrupted,
                    ) { section, sectionStart, _ ->
                        cleanSection(section, sectionStart, requireSectionPair = true, afterClosedSection = sectionStart > 0)
                    }
                    if (adjacent != null) return CleanedReply(adjacent, matchedOuterPair = true)
                }
                if (first == last && !requirePair && cleaned.body == part) {
                    val selection = cleanPartHeading?.invoke(part) {
                        isQuotedDispatchBlock(start + it) || isJoinedLiteral(it, true) || joinedLiterals.followsLiteralHeadingLead(start + it) ||
                            protectCaptionedPartHeading && hasOrphanClosingCaption(text, start + it, before = start)
                    } ?: HeadingSelection(part.indices)
                    val body = part.substring(selection.body)
                    val unwrapped = if (!cleaned.couldSpanToolCalls && !cleaned.matchedOuterPair &&
                        !explainsPreviousExample(last + 1)
                    ) {
                        cleanPartOrphan?.invoke(body) {
                            val localIndex = selection.body.first + it
                            val joinedIndex = start + localIndex
                            isJoinedLiteral(localIndex, true) || joinedLiterals.isLazyListItemLine(joinedIndex) ||
                                hasOrphanClosingCaption(text, joinedIndex, before = start)
                        } ?: body
                    } else body
                    return cleaned.copy(body = part.substring(0, selection.prefixEnd) + unwrapped)
                }
                return cleaned
            }
            return buildString {
                var first = 0
                var appendedPart = false
                while (first < parts.size) {
                    var cleaned = cleanParts(first, first)
                    var next = first + 1
                    val followsExampleLead = (cleaned.matchedOuterPair || cleaned.couldSpanToolCalls) &&
                        joinedLiterals.matchesVisibleLine(
                            previousVisibleLine(joinedLiterals.markdown, parts[first].first), metadataExampleBridge,
                        )
                    if (cleaned.matchedOuterPair &&
                        (followsExampleLead || explainsPreviousExample(next, afterFrame = true) || isBoundedLogQuotation(first, first))
                    ) {
                        cleaned = CleanedReply(text.substring(parts[first]))
                    }
                    val opening = cleaned.pendingToolOpening?.let { parts[first].first + it }
                    if (opening != null) {
                        val closingParts = toolCloses[opening].orEmpty().sortedBy { it.first }.asSequence().flatMap { close ->
                            val index = parts.binarySearchBy(close.first) { it.first }
                            val closePart = if (index < 0) -index - 2 else index
                            // An existing suffix can span calls, but plain prose after the close
                            // cannot become a recognized trailer by appending more calls.
                            val canExtend = parts[closePart].last >= close.last &&
                                canContinueReplySuffix(text, close.last + 1, parts[closePart].last + 1, trailerHeadings)
                            val limit = if (canExtend) parts.lastIndex else closePart
                            maxOf(next, closePart)..limit
                        }.distinct()
                        for (last in closingParts) {
                            val grouped = cleanParts(first, last, requirePair = true)
                            if (!grouped.matchedOuterPair && !grouped.preservedLiteralExample) continue
                            val explicitExample = followsExampleLead || explainsPreviousExample(last + 1, afterFrame = true) ||
                                isBoundedLogQuotation(first, last)
                            val ambiguousContinuation = if (!explicitExample && last < parts.lastIndex) {
                                val closingOffset = standaloneClosingOffset(text.substring(parts[last]))
                                if (closingOffset == null) {
                                    val following = cleanParts(last + 1, last + 1, followsClosedWrapper = true)
                                    !following.matchedOuterPair && !following.couldSpanToolCalls
                                } else {
                                    // A tool boundary supplies no evidence that the joined list has ended.
                                    val nextLine = parts[last + 1].first
                                    joinedLiterals.isLazyListItemLine(parts[last].first + closingOffset) &&
                                        nextLine in joinedLiterals.listItemLineStarts &&
                                        text.getOrNull(nextLine)?.isHorizontalPadding() == true
                                }
                            } else false
                            cleaned = if (explicitExample || ambiguousContinuation || grouped.preservedLiteralExample) {
                                CleanedReply(text.substring(parts[first].first, parts[last].last + 1))
                            } else grouped
                            next = last + 1
                            break
                        }
                        if (followsExampleLead && next == first + 1) {
                            cleaned = CleanedReply(text.substring(parts[first]))
                        }
                    }
                    if (cleaned.body.isNotEmpty() || next == first + 1 && parts[first].isEmpty()) {
                        if (appendedPart) append('\n')
                        append(cleaned.body)
                        appendedPart = true
                    }
                    // A genuine closing tag ends that reply's quoted paragraph before the next call.
                    previousGroupClosedWrapper = cleaned.matchedOuterPair
                    first = next
                }
            }
        }
        return cleanAdjacentHistoryPairs(
            text, allowedOpenings.orEmpty(), formatOutput.declaredOpenings, streaming || interrupted,
        ) { section, _, requirePair -> clean(section, requirePair) } ?: clean(text).body
    }

    private fun cleanAdjacentHistoryPairs(
        text: String,
        allowedOpenings: Set<String>,
        declaredOpenings: Set<String>,
        provisional: Boolean,
        clean: (section: String, start: Int, requirePair: Boolean) -> CleanedReply,
    ): String? {
        val openings = allowedOpenings - declaredOpenings
        if (openings.isEmpty()) return null
        val candidates = wrappers.filter { it.opening in openings }.distinctBy(Wrapper::name)
        val closings = candidates.flatMap(Wrapper::closings)
        val openingPrefixes = candidates.map { if (provisional) it.opening.take(2) else it.opening.substringBefore('"') }
        val firstCloseEnd = closings.mapNotNull { token ->
            text.indexOf(token).takeIf { it >= 0 }?.plus(token.length)
        }.minOrNull() ?: return null
        if (openingPrefixes.none { text.indexOf(it, firstCloseEnd) >= 0 }) return null
        val tags = candidates.flatMap { wrapperFamilyTokens.getValue(it.name).findAll(text).toList() }.sortedBy { it.range.first }
        val literals = classifyLiterals(text, tags, closings, includeUnclosed = provisional)
        val standalone = tags.filter { tag ->
            literals.isVisibleWrapperTag(text, tag) &&
                literals.isStandaloneTagLine(tag.range.first, tag.value) &&
                !literals.isLazyListItemLine(tag.range.first) &&
                (tag.value !in closings || !literals.isCaptionedClosing(tag.range.first))
        }
        fun adjacent(end: Int, start: Int): Boolean {
            val gap = text.subSequence(end, start)
            if (gap.none { it.isHistoryLineBreak() }) return false
            if (gap.isBlank()) return true

            val comments = literals.commentRanges
            val first = comments.binarySearchBy(end, selector = IntRange::first)
            var cursor = end
            for (index in (if (first >= 0) first else -first - 1) until comments.size) {
                val comment = comments[index]
                if (comment.first >= start) break
                val after = comment.last + 1
                if (after > start || !text.subSequence(cursor, comment.first).isBlank() ||
                    !hasMarkerPadding(text, comment.first) ||
                    !text.subSequence(after, lineEnd(text, after)).isBlank()
                ) return false
                cursor = after
            }
            return text.subSequence(cursor, start).isBlank()
        }

        fun cleanPairs(eligible: List<MatchResult>, allowLazyQuote: Boolean): String? {
            val boundaries = eligible.zipWithNext().mapNotNull { (close, open) ->
                val end = close.range.last + 1
                val start = open.range.first
                if (close.value in closings && open.value in openings && adjacent(end, start)) end to start else null
            }.toMutableList()
            if (provisional) {
                // Keep already proven text visible while the next opener is arriving.
                val previousClose = eligible.lastOrNull()?.takeIf { it.value in closings }
                if (previousClose != null) {
                    val end = previousClose.range.last + 1
                    val start = (end until text.length).firstOrNull { !literals.markdown[it].isWhitespace() }
                    if (start != null && adjacent(end, start) && hasMarkerPadding(text, start) &&
                        !literals.ranges.containsOffset(start) && !literals.isLazyListItemLine(start) &&
                        !literals.isMarkdownContainerLine(start, includeStandaloneLazyTags = !allowLazyQuote) &&
                        text.substring(start).let { prefix -> openings.any { it.startsWith(prefix) } } &&
                        openingPrefixes.any { text.startsWith(it, start) }
                    ) boundaries += end to start
                }
            }
            if (boundaries.isEmpty()) return null

            // Every preceding section must prove its own outer pair before the next one can
            // start a separate reply, including a quoted paragraph that syntactically continues.
            val result = StringBuilder()
            var start = 0
            var previousEnd = 0
            for ((end, next) in boundaries + (text.length to text.length)) {
                val section = text.substring(start, end)
                val last = end == text.length
                val cleaned = clean(section, start, !last || !provisional)
                // A proven authored pair can be intentionally unchanged. An unmatched, unchanged
                // section still leaves the whole split uncertain.
                if (!cleaned.matchedOuterPair && (cleaned.body == section || !last || !provisional)) return null
                val gap = text.subSequence(previousEnd, start)
                if (result.isNotEmpty() && cleaned.body.isNotEmpty() || gap.isNotBlank()) result.append(gap)
                result.append(cleaned.body)
                previousEnd = end
                start = next
            }
            return result.toString()
        }
        val outsideQuotes = standalone.filterNot { literals.isLazyQuoteLine(it.range.first) }
        return cleanPairs(outsideQuotes, allowLazyQuote = false) ?: if (outsideQuotes.size != standalone.size) {
            cleanPairs(standalone, allowLazyQuote = true)
        } else null
    }

    fun replyBody(
        text: String,
        streaming: Boolean = false,
        enabled: Boolean = true,
        interrupted: Boolean = false,
    ): String = if (enabled) cleanReply(text, streaming, interrupted).body else text

    private fun cleanReply(
        text: String,
        streaming: Boolean,
        interrupted: Boolean,
        requirePair: Boolean = false,
        formatOutput: FormatOutput = FormatOutput(),
        projectedDuplicateOpenings: Set<String> = emptySet(),
        projectedLegacyInners: Set<String> = emptySet(),
        projectedBodies: Map<String, String> = emptyMap(),
        isJoinedLiteral: ((index: Int, opening: Boolean) -> Boolean)? = null,
        allowedOpenings: Set<String>? = null,
        repairProtectedClosings: Map<String, Set<String>> = emptyMap(),
        literalOutput: LiteralOutput = LiteralOutput(),
        trailerHeadings: List<String> = emptyList(),
        trailerContinuations: Map<String, Set<List<String>>> = emptyMap(),
        cleanExposedBody: (String) -> String = { it },
    ): CleanedReply {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return CleanedReply(text)
        val leadingStatus by lazy(LazyThreadSafetyMode.NONE) {
            classifyLiterals(text, interpretProseQuotes = false).fences.filter { it.topLevel && it.legacyStatus }
        }
        var openingStart = first
        while (openingStart < text.length) {
            openingStart = when {
                text.startsWith(commentOpening, openingStart) && !hasCodeIndent(text, openingStart) ->
                    closedCommentEnd(text, openingStart) ?: return CleanedReply(text)
                text.startsWith("```", openingStart) && MarkdownCodeFences.legacyStatusLabel in text -> {
                    val line = lineStart(text, openingStart)
                    val status = leadingStatus.firstOrNull { it.range.first == line } ?: break
                    status.range.last + 1
                }
                else -> break
            }
            while (openingStart < text.length && text[openingStart].isWhitespace()) openingStart++
        }
        if (openingStart == text.length || hasCodeIndent(text, openingStart)) return CleanedReply(text)
        val leadingContent = if (openingStart == first) "" else text.substring(0, openingStart)
        val start = text.substring(openingStart)
        val candidates = if (allowedOpenings == null) wrappers else wrappers.filter { it.opening in allowedOpenings }
        val wrapper = candidates.firstOrNull { start.startsWith(it.opening) }
        if (wrapper == null) {
            val identifiable = start.startsWith(modernOpeningPrefix) || start.startsWith("<聚焦模式=")
            if (!requirePair && (streaming || identifiable || interrupted && start.length > 1) &&
                candidates.any { it.opening !in repairProtectedClosings && it.opening.startsWith(start) } &&
                formatOutput.declaredOpenings.none { it.startsWith(start) } &&
                literalOutput.openings.none { it.startsWith(start) } &&
                isJoinedLiteral?.invoke(openingStart, true) != true
            ) return CleanedReply(leadingContent)
            return CleanedReply(text)
        }
        if (isJoinedLiteral?.invoke(openingStart, true) == true) return CleanedReply(text)
        if (wrapper.opening !in formatOutput.declaredOpenings && text !in literalOutput.pairs) {
            projectedBodies[text]?.let { return CleanedReply(it, matchedOuterPair = true) }
        }
        val afterOpening = start.substring(wrapper.opening.length)
        val openingLineBreak = afterOpening.wrapperLineBreak(0)
        val openerOnOwnLine = openingLineBreak != null
        val body = if (openingLineBreak == null) afterOpening else afterOpening.substring(openingLineBreak).trimStart('\r', '\n')
        val bodyStart = text.length - body.length
        val end = body.trimEnd()
        val familyTags = wrapperFamilyTokens.getValue(wrapper.name).findAll(end).toList()
        val protectedRepairTokens = repairProtectedClosings[wrapper.opening].orEmpty()
        val literals by lazy(LazyThreadSafetyMode.NONE) {
            classifyLiterals(end, familyTags, wrapper.closings, includeUnclosed = streaming || interrupted)
        }
        val rawVisibleTags = familyTags.filter { tag ->
            literals.isVisibleWrapperTag(end, tag, allowIndentedParagraphTag = true) &&
                (tag.value !in wrapper.closings || !literals.isCaptionedClosing(tag.range.first, literalOutput.closings)) &&
                isJoinedLiteral?.invoke(bodyStart + tag.range.first, false) != true
        }
        val suffixRanges by lazy(LazyThreadSafetyMode.NONE) {
            literals.replySuffixRanges(formatOutput, streaming || interrupted)
        }
        val trailerHeading by lazy(LazyThreadSafetyMode.NONE) {
            if (trailerHeadings.none { it in body }) null else headingBeforeTrailer(
                body, trailerHeadings, streaming || interrupted, formatOutput, literals, suffixRanges, trailerContinuations,
            ) { isJoinedLiteral?.invoke(bodyStart + it, true) == true }
        }
        // A sent heading may separate a genuine end marker from the recognized status or action trailer.
        fun headingAfterClose(suffixStart: Int): IntRange? = trailerHeading?.takeIf {
            suffixStart <= it.first && body.subSequence(suffixStart, it.first).isBlank()
        }
        fun isProjectedOwnLineFrame(position: Int, close: String): Boolean =
            openingLineBreak != null && wrapper != legacyWrapper &&
                projectedBodies.keys.any { it.startsWith(wrapper.opening) } && literals.isStandaloneTagLine(position, close)

        fun endsWithLiteralFence(position: Int): Boolean {
            val fence = literals.fences.lastOrNull() ?: return false
            return fence.topLevel && fence.closingLineStart != null && fence.range in literals.roleplayLiteralCode &&
                fence.range.last < position && body.subSequence(fence.range.last + 1, position).isBlank()
        }

        fun bodyBeforeClose(position: Int, completeClose: String? = null): String {
            val trimmed = body.substring(0, position).trimEnd()
            val openingBreak = openingLineBreak
            if (completeClose != null && openingBreak != null && isProjectedOwnLineFrame(position, completeClose)) {
                val breakAfterOpening = if (afterOpening.startsWith("\r\n", openingBreak)) 2 else 1
                val contentStart = openingStart + wrapper.opening.length + openingBreak + breakAfterOpening
                val closingLine = bodyStart + lineStart(body, position)
                // Only the envelope owns the two adjacent line breaks.
                val breakBeforeClosing = if (closingLine >= 2 && text.startsWith("\r\n", closingLine - 2)) 2 else 1
                val payload = text.substring(contentStart, maxOf(contentStart, closingLine - breakBeforeClosing))
                val hasCrLfInsideBody = "\r\n" in payload.trim('\r', '\n')
                val hasPaddedTerminalCodeLine = payload.endsWith('\n') &&
                    body.startsWith("  ", trimmed.length) && literals.indentedCode.containsOffset(trimmed.lastIndex)
                if (hasCrLfInsideBody || markdownHardBreakBetweenLines.containsMatchIn(payload) ||
                    hasPaddedTerminalCodeLine || endsWithLiteralFence(position)
                ) {
                    return payload
                }
            }
            val trailingLiteral = literals.roleplayLiteralCode.firstOrNull {
                trimmed.lastIndex in it && trimmed.length in it
            } ?: return trimmed
            val limit = minOf(position, trailingLiteral.last + 1)
            var endOfContent = trimmed.length
            while (endOfContent < limit && !body[endOfContent].isHistoryLineBreak()) endOfContent++
            return body.substring(0, endOfContent)
        }
        fun blankTailAfterFencedBody(position: Int, afterClosing: Int, close: String): String {
            if (!body.getOrNull(afterClosing).isHistoryLineBreak() ||
                !isProjectedOwnLineFrame(position, close) || !endsWithLiteralFence(position)
            ) return ""
            return body.substring(afterClosing)
        }
        fun endsReply(tag: MatchResult): Boolean =
            tag.range.last + 1 == end.length ||
                hasOnlyReplySuffix(
                    body, tag.range.last + 1, suffixRanges, literals, streaming || interrupted,
                    formatOutput,
                ) || headingAfterClose(tag.range.last + 1) != null

        val visibleTags = if (literalOutput.openings.isEmpty() && literalOutput.closings.isEmpty() ||
            !openerOnOwnLine
        ) rawVisibleTags else {
            val last = rawVisibleTags.lastOrNull()
            val terminalClose = last?.takeIf { it.value in wrapper.closings && endsReply(it) }
            val ordinaryClose = wrapperBoundary(wrapper, rawVisibleTags.asSequence()).closing
            val requestedClose = rawVisibleTags.firstOrNull { tag ->
                tag.value in literalOutput.closings && literals.isStandaloneTagLine(tag.range.first, tag.value) &&
                    (lineStart(body, tag.range.first) == 0 || terminalClose != null &&
                        tag == ordinaryClose && tag != terminalClose)
            }
            val withoutRequestedCloses = rawVisibleTags.filterNot { it == requestedClose }
            val requestedOpen = if (terminalClose != null &&
                wrapperBoundary(wrapper, withoutRequestedCloses.asSequence()).closing == null
            ) withoutRequestedCloses.singleOrNull { tag ->
                tag.value in literalOutput.openings && literals.isStandaloneTagLine(tag.range.first, tag.value)
            } else null
            withoutRequestedCloses.filterNot { it == requestedOpen }
        }

        val terminal = visibleTags.lastOrNull()?.takeIf { tag ->
            tag.value in wrapper.closings && !literals.isLazyQuoteLine(tag.range.first) && endsReply(tag)
        }
        // A proven close outside the Markdown container leaves its earlier lazy lines literal.
        val outsideContainer = terminal?.let {
            val outsideList = !literals.isLazyListItemLine(it.range.first)
            wrapperBoundary(wrapper, visibleTags.asSequence().filterNot { tag ->
                tag.value in wrapper.closings && (literals.isLazyQuoteLine(tag.range.first) ||
                    outsideList && literals.isLazyListItemLine(tag.range.first))
            }).takeIf { it.closing == terminal }
        }
        val boundary = outsideContainer ?: wrapperBoundary(wrapper, visibleTags.asSequence())
        val closing = boundary.closing
        val exactAuthoredPair = closing != null && literalOutput.pairs.isNotEmpty() &&
            text.substring(openingStart, bodyStart + closing.range.last + 1) in literalOutput.pairs
        val pendingAuthoredPair = !exactAuthoredPair && (streaming || interrupted) &&
            body.isNotBlank() && literalOutput.pairs.isNotEmpty() && start.trimEnd().let { prefix ->
                // The matching authored sample may already prove that a live apparent close is
                // inside a literal whose ending delimiter has yet to arrive.
                val apparentClose = closing?.let { bodyStart + it.range.first - openingStart }
                literalOutput.pairs.any { pair ->
                    pair.startsWith(prefix) &&
                        (apparentClose == null || classifyLiterals(pair).ranges.containsOffset(apparentClose))
                }
            }
        if (exactAuthoredPair || pendingAuthoredPair) {
            return CleanedReply(
                text, matchedOuterPair = exactAuthoredPair && endsReply(closing),
                pendingToolOpening = openingStart.takeIf { pendingAuthoredPair },
            )
        }
        val declared = wrapper.opening in formatOutput.declaredOpenings &&
            !(wrapper.opening in projectedDuplicateOpenings &&
                openingStart == first && openerOnOwnLine &&
                (closing != null || streaming || interrupted) &&
                wrapper.matchesProjectedDuplicate(end, visibleTags))
        val witnessedDoubleTransport = wrapper != legacyWrapper && wrapper.opening !in formatOutput.declaredOpenings &&
            wrapper.opening in projectedDuplicateOpenings && openerOnOwnLine &&
            (wrapper.matchesProjectedDuplicate(end, visibleTags) &&
                (end.length == wrapper.opening.length || end.getOrNull(wrapper.opening.length).isHistoryLineBreak()) &&
                visibleTags.drop(1).all { end.getOrNull(it.range.first - 1).isHistoryLineBreak() } ||
                (streaming || interrupted) && end.length < wrapper.opening.length &&
                wrapper.opening.startsWith(end) && isJoinedLiteral?.invoke(bodyStart, true) != true)

        fun removeInnerTransport(inner: String, requireInnerPair: Boolean): String {
            val witnessedInner = if (witnessedDoubleTransport) wrapper else wrappers.firstOrNull {
                it == legacyWrapper && wrapper.opening in projectedLegacyInners &&
                    it.opening !in formatOutput.declaredOpenings &&
                    openerOnOwnLine &&
                    (if (requireInnerPair) it.isStandalonePair(inner) else
                        it.opening.startsWith(inner) || inner.startsWith(it.opening) &&
                            (inner.length == it.opening.length || inner.getOrNull(it.opening.length).isHistoryLineBreak()))
            } ?: return inner
            val result = cleanReply(
                inner, streaming, interrupted, requirePair = requireInnerPair, formatOutput = formatOutput,
                isJoinedLiteral = isJoinedLiteral?.let { joined -> { index, opening -> joined(bodyStart + index, opening) } },
                allowedOpenings = setOf(witnessedInner.opening), literalOutput = literalOutput,
                repairProtectedClosings = repairProtectedClosings,
                trailerHeadings = trailerHeadings,
                trailerContinuations = trailerContinuations,
                cleanExposedBody = cleanExposedBody,
            )
            return if (result.matchedOuterPair) result.body + inner.takeLastWhile(Char::isWhitespace) else result.body
        }

        if (closing != null) {
            val position = closing.range.first
            val afterClosing = closing.range.last + 1
            // A complete leading pair followed by visible prose is an example.
            if (!endsReply(closing)) {
                val nextLine = nextNonblankLine(literals.markdown, afterClosing)
                val explained = literals.matchesVisibleLine(nextLine, historyExampleExplanation) ||
                    literals.matchesVisibleLine(nextLine, historyElementAttributeExplanation)
                return CleanedReply(
                    text,
                    pendingToolOpening = openingStart,
                    preservedLiteralExample = explained && body.subSequence(afterClosing, lineEnd(body, afterClosing)).isBlank(),
                )
            }
            if (declared) return CleanedReply(text, matchedOuterPair = true)
            // A visible input delimiter reserves its role. Only remove an independently matched
            // outer repair pair when the same token demonstrably survives inside a body literal.
            if (protectedRepairTokens.any { token ->
                    familyTags.none { tag ->
                        tag.value == token && tag.range.last < position &&
                            !literals.isVisibleWrapperTag(end, tag, allowIndentedParagraphTag = true)
                    }
                }
            ) return CleanedReply(text)
            if (afterClosing == end.length) {
                val content = projectedBodies[text] ?:
                    cleanExposedBody(removeInnerTransport(bodyBeforeClose(position, closing.value), requireInnerPair = true))
                val tail = blankTailAfterFencedBody(position, afterClosing, closing.value)
                return CleanedReply(leadingContent + content + tail, matchedOuterPair = true)
            }
            val tail = headingAfterClose(afterClosing)?.let { heading ->
                body.substring(afterClosing, heading.first).trimEnd('\r', '\n') + body.substring(heading.last + 1)
            } ?: body.substring(afterClosing)
            val markerLineStart = lineStart(body, position)
            val markerStartsLine = body.subSequence(markerLineStart, position).isBlank()
            val suffixSharesLine = tail.lineSequence().first().isNotBlank()
            val before = if (markerStartsLine && suffixSharesLine) {
                body.substring(0, markerLineStart)
            } else {
                bodyBeforeClose(position, closing.value)
            }
            val exposed = removeInnerTransport(before, requireInnerPair = true)
            val cleaned = cleanExposedBody(exposed)
            val separator = if (markerStartsLine && suffixSharesLine &&
                cleaned.isNotEmpty() && !cleaned.last().isHistoryLineBreak()
            ) exposed.takeLastWhile { it.isHistoryLineBreak() } else ""
            return CleanedReply(
                leadingContent + cleaned + separator + tail,
                matchedOuterPair = true,
            )
        }

        if (declared || requirePair || protectedRepairTokens.isNotEmpty()) return CleanedReply(text, pendingToolOpening = openingStart)
        val sameLineContent = end.isNotEmpty() && !openerOnOwnLine
        val lastTag = end.lastIndexOf('<')
        if (lastTag >= 0 && boundary.canEndWithPartialClose) {
            val suffix = end.substring(lastTag)
            val identifiable = suffix.startsWith("</${wrapper.name}")
            val aloneOnLine = end.substring(0, lastTag).substringAfterLast('\n').substringAfterLast('\r').isBlank()
            val standalonePrefix = allowedOpenings != null && openerOnOwnLine &&
                literals.isStandaloneTagLine(lastTag, suffix) && isIdentifiableClosingPrefix(suffix, wrapper.name)
            // Same-line prose can explain the opener. A named marker on its own line can finish the witnessed frame.
            if ((!sameLineContent || lastTag == 0 || identifiable) &&
                (streaming || identifiable || standalonePrefix || interrupted && (suffix.length > 1 || aloneOnLine)) &&
                wrapper.closings.any { suffix.length < it.length && it.startsWith(suffix) } &&
                !isEscaped(end, lastTag) && !literals.isMarkdownContainerLine(lastTag) &&
                !literals.isCaptionedClosing(lastTag, literalOutput.closings) &&
                !(openerOnOwnLine && lineStart(body, lastTag) == 0 &&
                    literals.isStandaloneTagLine(lastTag, suffix) &&
                    (literalOutput.openings + literalOutput.closings).any { it.startsWith(suffix) }) &&
                !literals.ranges.containsOffset(lastTag) && !literals.hasCodeIndentAt(lastTag) &&
                isJoinedLiteral?.invoke(bodyStart + lastTag, false) != true
            ) {
                val before = bodyBeforeClose(lastTag)
                val cleaned = if (streaming || interrupted) removeInnerTransport(before, requireInnerPair = false) else before
                return CleanedReply(leadingContent + cleanExposedBody(cleaned), pendingToolOpening = openingStart)
            }
        }
        if (wrapper.opening in literalOutput.openings &&
            (afterOpening.isEmpty() || openerOnOwnLine) &&
            visibleTags.none { it.value in wrapper.closings }
        ) {
            val firstBodyLine = body.subSequence(0, lineEnd(body, 0)).trim()
            val alreadyContainsRequestedOpening = firstBodyLine.isNotEmpty() &&
                literalOutput.openings.any { it.startsWith(firstBodyLine) }
            if (!alreadyContainsRequestedOpening) return CleanedReply(text, pendingToolOpening = openingStart)
        }
        val cleaned = if (streaming || interrupted) removeInnerTransport(body, requireInnerPair = false) else body
        return CleanedReply(
            if (sameLineContent) text else leadingContent + cleanExposedBody(cleaned),
            pendingToolOpening = openingStart,
        )
    }

    private fun Wrapper.matchesProjectedDuplicate(body: String, tags: List<MatchResult>): Boolean {
        val openingTag = tags.firstOrNull() ?: return false
        if (openingTag.range.first != 0 || openingTag.value != opening) return false
        val closingTags = tags.drop(1)
        if (closingTags.size > 2 || closingTags.any { it.value !in closings }) return false
        val innerClose = closingTags.firstOrNull() ?: return true
        val outerClose = closingTags.getOrNull(1)
        val tail = body.subSequence(innerClose.range.last + 1, outerClose?.range?.first ?: body.length)
        if (outerClose != null) return tail.isBlank() && tail.any { it.isHistoryLineBreak() }
        if (tail.isBlank()) return true

        // A pending outer close can follow the inner pair, but prose invalidates the duplicate shape.
        val prefix = tail.trimStart()
        return tail.take(tail.length - prefix.length).any { it.isHistoryLineBreak() } &&
            closings.any { it.startsWith(prefix) }
    }

    private fun wrapperBoundary(wrapper: Wrapper, tags: Sequence<MatchResult>): WrapperBoundary {
        var innerDepth = 0
        for (tag in tags) {
            if (tag.value.startsWith("</")) {
                if (innerDepth > 0) {
                    innerDepth--
                } else {
                    return WrapperBoundary(tag.takeIf { it.value in wrapper.closings }, canEndWithPartialClose = false)
                }
            } else if (!tag.value.dropLast(1).trimEnd().endsWith('/')) {
                innerDepth++
            }
        }
        return WrapperBoundary(closing = null, canEndWithPartialClose = innerDepth == 0)
    }

    private fun toolClosesByOpening(
        text: String, families: Map<String, List<MatchResult>>, literals: ReplyLiterals,
        literalOutput: LiteralOutput,
    ): Map<Int, Set<IntRange>> {
        val matches = mutableMapOf<Int, MutableSet<IntRange>>()
        val optionalContinuationLines = listOf(
            emptySet(), literals.lazyQuoteLineStarts, literals.lazyQuoteLineStarts + literals.lazyListItemLineStarts,
        ).distinct()
        val preserveClosingChoices = if (literalOutput.closings.isEmpty()) listOf(false) else listOf(false, true)
        fun record(open: MatchResult, close: MatchResult) {
            val wrapper = wrappers.firstOrNull { it.opening == open.value } ?: return
            if (close.value in wrapper.closings) matches.getOrPut(open.range.first, ::mutableSetOf).add(close.range)
        }
        for (tags in families.values) {
            val visible = tags.filter { literals.isVisibleWrapperTag(text, it, allowIndentedParagraphTag = true) }
            for (ignoredClosings in optionalContinuationLines) {
                for (preserveRequestedClosing in preserveClosingChoices) {
                    val openings = ArrayDeque<MatchResult>()
                    val skippedClosingFor = mutableSetOf<Int>()
                    for (tag in visible) {
                        if (tag.value.startsWith("</")) {
                            if (literals.isCaptionedClosing(tag.range.first, literalOutput.closings) ||
                                lineStart(literals.markdown, tag.range.first) in ignoredClosings
                            ) continue
                            val open = openings.lastOrNull() ?: continue
                            if (preserveRequestedClosing && tag.value in literalOutput.closings &&
                                literals.isStandaloneTagLine(tag.range.first, tag.value) &&
                                skippedClosingFor.add(open.range.first)
                            ) continue
                            openings.removeLast()
                            record(open, tag)
                            // This close may end the preceding outer wrapper when its apparent
                            // nested opener was requested literal text. The candidate still needs
                            // to pass the regular boundary check on its exact span.
                            val parent = openings.lastOrNull()
                            if (parent != null && open.value in literalOutput.openings &&
                                literals.isStandaloneTagLine(open.range.first, open.value)
                            ) record(parent, tag)
                        } else if (!tag.value.dropLast(1).trimEnd().endsWith('/')) {
                            openings.addLast(tag)
                        }
                    }
                }
            }
        }
        return matches
    }

    private fun canContinueReplySuffix(
        text: String, from: Int, limit: Int, trailerHeadings: List<String>,
    ): Boolean {
        val start = (from until limit).firstOrNull { !text[it].isWhitespace() } ?: return false
        // Only a real comment opener can introduce an angle-prefixed trailer across call boundaries.
        val startsComment = limit - start >= commentOpening.length && text.startsWith(commentOpening, start)
        return startsComment || text[start] in "`-—―─－" || trailerHeadings.any { it.first() == text[start] }
    }

    private fun ReplyLiterals.isVisibleWrapperTag(
        text: String,
        tag: MatchResult,
        includeStandaloneLazyTags: Boolean = false,
        allowIndentedParagraphTag: Boolean = false,
    ): Boolean {
        val position = tag.range.first
        val quote = text.getOrNull(position - 1)
        val asciiQuoted = (quote == '"' || quote == '\'') && text.getOrNull(tag.range.last + 1) == quote
        return !asciiQuoted && !isEscaped(text, position) && !ranges.containsOffset(position) &&
            !isMarkdownContainerLine(position, includeStandaloneLazyTags) && !hasCodeIndentAt(position, allowIndentedParagraphTag)
    }

    private fun ReplyLiterals.hasCodeIndentAt(offset: Int, allowIndentedParagraphTag: Boolean = true): Boolean {
        val start = lineStart(markdown, offset)
        val startsParagraphLine = allowIndentedParagraphTag && start in indentedParagraphLineStarts &&
            markdown.subSequence(start, offset).isBlank()
        return !startsParagraphLine && hasCodeIndent(markdown, offset)
    }

    private fun ReplyLiterals.isLazyQuoteLine(offset: Int): Boolean =
        lineStart(markdown, offset) in lazyQuoteLineStarts

    private fun ReplyLiterals.isLazyListItemLine(offset: Int): Boolean =
        lineStart(markdown, offset) in lazyListItemLineStarts

    private fun ReplyLiterals.isCaptionedClosing(offset: Int, requested: Set<String> = emptySet()): Boolean {
        val start = lineStart(markdown, offset)
        val lazyList = start in lazyListItemLineStarts
        val lazyQuote = start in lazyQuoteLineStarts
        val fromContainer = lazyList || lazyQuote
        val hasPadding = if (fromContainer) markdown.subSequence(start, offset).isBlank() else hasMarkerPadding(markdown, offset)
        if (!hasPadding) return false
        val lead = previousVisibleLine(markdown, start) ?: return false
        val caption = if (fromContainer) markdown.substring(lead) else visibleLine(lead)
        val match = caption?.let(literalClosingTagLead::find) ?: return false
        if (lazyList) return true
        if (markdown.substring(lead.last + 1, start) !in setOf("\n", "\r\n")) return false
        val tagLine = markdown.substring(offset, lineEnd(markdown, offset)).trimEnd()
        // Unquoted prose needs a literal declaration or a matching request. A later token may still close the frame.
        return tagLine.isNotEmpty() && if (lazyQuote || match.groups[1] != null) {
            wrapperClosings.any { it.startsWith(tagLine) }
        } else {
            requested.any { it.startsWith(tagLine) }
        }
    }

    private fun ReplyLiterals.isStandaloneTagLine(offset: Int, token: String): Boolean {
        if (!hasMarkerPadding(markdown, offset)) return false
        val end = offset + token.length
        return markdown.subSequence(end, lineEnd(markdown, end)).isBlank()
    }

    private fun ReplyLiterals.isMarkdownContainerLine(offset: Int, includeStandaloneLazyTags: Boolean = false): Boolean {
        val start = lineStart(markdown, offset)
        if (start in listItemLineStarts || MarkdownCodeFences.hasQuoteContainerPrefix(markdown, start)) return true
        if (start !in lazyQuoteLineStarts) return false
        // A standalone closing marker can still end a wrapper after a quoted paragraph.
        return includeStandaloneLazyTags || (start until offset).any { index ->
            !markdown[index].isWhitespace() && commentRanges.none { index in it }
        }
    }

    private fun ReplyLiterals.replySuffixRanges(formatOutput: FormatOutput, provisional: Boolean): List<IntRange> {
        val status = fences.filter {
            it.topLevel && it.legacyStatus || when (val info = it.statusInfo()) {
                null -> false
                "" -> it.matchesBareStatusLead(markdown, formatOutput.trailingBareStatusLeads, provisional)
                else -> info in formatOutput.trailingStatusInfos
            }
        }.map { it.range }
        val options = (roleplayAnnotatedActionRanges(markdown).asSequence() +
            roleplayListedActionRanges(markdown)).filter { range ->
            !ranges.containsOffset(range.first) && !ranges.containsOffset(range.last) &&
                !isMarkdownContainerLine(range.first) && !isMarkdownContainerLine(range.last)
        }.toList()
        return (commentRanges + status + options).sortedBy { it.first }
    }

    private fun hasOnlyReplySuffix(
        text: String,
        start: Int,
        suffixRanges: List<IntRange>,
        literals: ReplyLiterals,
        provisional: Boolean,
        formatOutput: FormatOutput,
        requireTrailer: Boolean = false,
    ): Boolean {
        var next = start
        var foundTrailer = false
        for (range in suffixRanges) {
            if (range.first < start || range.last < next) continue
            if (range.first > next && !text.subSequence(next, range.first).isBlank()) break
            next = range.last + 1
            if (requireTrailer && range !in literals.commentRanges) foundTrailer = true
        }
        val remainder = text.substring(next).trimStart()
        if (remainder.isEmpty()) return !requireTrailer || foundTrailer
        if (provisional && commentOpening.startsWith(remainder.trimEnd())) return !requireTrailer || foundTrailer
        if (!provisional && !formatOutput.declaresAnnotatedActions) return false

        val position = text.length - remainder.length
        val line = lineStart(text, position)
        val indent = text.subSequence(line, position)
        if (indent.length > 3 || indent.any { it != ' ' }) return false

        // A status opener can arrive a token at a time. A newline already committed to ordinary code stays literal.
        if (provisional && (MarkdownCodeFences.legacyStatusOpening.startsWith(remainder) ||
                formatOutput.trailingStatusInfos.any { "```$it".startsWith(remainder, ignoreCase = true) } ||
                formatOutput.trailingBareStatusLeads.isNotEmpty() && "```".startsWith(remainder))
        ) return true
        if (!literals.isUnprotected(position)) return false
        val action = literals.markdown.substring(line) + text.substring(literals.markdown.length)
        return formatOutput.declaresAnnotatedActions && isPendingAnnotatedRoleplayActionOpening(action) ||
            provisional && isPendingRoleplayActionSuffix(action)
    }

    internal fun isStandaloneLegacyHistoryWrapper(text: String): Boolean {
        if (!legacyWrapper.isStandalonePair(text)) return false
        return cleanReply(text, streaming = false, interrupted = false, requirePair = true).matchedOuterPair
    }

    private fun Char.isPlainPreviewCharacter(): Boolean =
        isLetterOrDigit() || this in " .,!?;:，。！？；：、"

    private fun Char.isPlainPreviewBodyCharacter(): Boolean = isHistoryLineBreak() || isPlainPreviewCharacter()

    private fun String.hasPlainPreviewWithInlineClosingTag(from: Int): Boolean {
        // Ordinary single-line prose anchors the span outside Markdown block syntax. Once a
        // single exact closing tag is enclosed in backticks, plain appends cannot reclassify it.
        if (getOrNull(from)?.isLetterOrDigit() != true) return false
        val openingTick = indexOf('`', from)
        if (openingTick < 0 || (from until openingTick).any { !this[it].isPlainPreviewCharacter() }) return false
        val closingTick = indexOf('`', openingTick + 1)
        val matchesTag = wrapperClosings.any {
            closingTick == openingTick + 1 + it.length && regionMatches(openingTick + 1, it, 0, it.length)
        }
        return matchesTag && (closingTick + 1 until length).all { this[it].isPlainPreviewBodyCharacter() }
    }

    private fun Char?.isHistoryLineBreak(): Boolean = this == '\n' || this == '\r'

    private fun Char.isHorizontalPadding(): Boolean = this == ' ' || this == '\t'

    private fun String.wrapperLineBreak(start: Int): Int? {
        var offset = start
        while (getOrNull(offset)?.isHorizontalPadding() == true) offset++
        return offset.takeIf { getOrNull(it).isHistoryLineBreak() }
    }

    private fun hasMarkerPadding(text: String, position: Int): Boolean {
        if (position < 0) return false
        val start = lineStart(text, position)
        return position - start <= 3 && (start until position).all { text[it] == ' ' }
    }

    private fun standaloneClosingOffset(text: String): Int? {
        val end = text.trimEnd()
        val offset = end.lastIndexOf('<')
        return offset.takeIf { hasMarkerPadding(end, it) && end.substring(it) in wrapperClosings }
    }

    /** Closed comments hidden in the bubble. Code and escaped comment openers are literal. */
    internal fun hiddenHtmlCommentRanges(text: String): List<IntRange> =
        if (commentOpening !in text) emptyList() else classifyLiterals(text, interpretProseQuotes = false).commentRanges

    /** Preserve Markdown code while keeping legacy status fences eligible for roleplay parsing. */
    internal fun roleplayLiteralCodeRanges(text: String): List<IntRange> {
        if ('`' !in text && '~' !in text && '\t' !in text && "    " !in text && cdataOpening !in text) {
            return emptyList()
        }
        val code = classifyLiterals(text, interpretProseQuotes = false).roleplayLiteralCode
        return buildList {
            for (range in code.sortedBy { it.first }) {
                val previous = lastOrNull()
                if (previous != null && (previous.last + 1 until range.first).all { text[it].isWhitespace() }) {
                    this[lastIndex] = previous.first..maxOf(previous.last, range.last)
                } else {
                    add(range)
                }
            }
        }
    }

    private fun classifyLiterals(
        end: String,
        familyTags: List<MatchResult> = emptyList(),
        closings: List<String> = emptyList(),
        includeUnclosed: Boolean = false,
        interpretProseQuotes: Boolean = true,
    ): ReplyLiterals {
        val markdown = bareCarriageReturn.replace(end, "\n")
        val comments = mutableListOf<IntRange>()
        val cdata = mutableListOf<IntRange>()
        val pendingMarkup = mutableListOf<LiteralMarkup>()
        val hasMarkup = commentOpening in markdown || cdataOpening in markdown
        val markupCanChangeMarkdown = hasMarkup && ('`' in markdown || markdownContainerOrIndent.containsMatchIn(markdown))

        fun inspect(source: String): ReplyLiterals {
            val lazyQuoteLineStarts = mutableSetOf<Int>()
            val containerLineStarts = mutableSetOf<Int>()
            val listItemLineStarts = mutableSetOf<Int>()
            val lazyListItemLineStarts = mutableSetOf<Int>()
            val indentedParagraphLineStarts = mutableSetOf<Int>()
            val indentedCode = mutableListOf<IntRange>()
            val fences = MarkdownCodeFences.blocks(
                source,
                onLazyQuoteLine = { lazyQuoteLineStarts += it },
                onIndentedCodeLine = { indentedCode += it },
                onContainerLine = { if (hasMarkup) containerLineStarts += it },
                onListItemLine = { listItemLineStarts += it },
                onIndentedParagraphLine = { indentedParagraphLineStarts += it },
                onLazyListItemLine = { lazyListItemLineStarts += it },
            )
            val codeBlocks = indentedCode + fences.map { it.range }
            val inlineCode = inlineCodeRangesOutsideBlocks(source, codeBlocks, indentedParagraphLineStarts)
            val literalFences = fences.filter { fence ->
                val statusKind = statusFenceInfo.matchEntire(fence.info)?.groupValues?.get(1)
                val nestedPlainCode = !fence.topLevel &&
                    (fence.info.isEmpty() || statusKind.equals("text", ignoreCase = true))
                fence.marker == '~' || fence.markerLength > 3 || nestedPlainCode ||
                    fence.info.isNotEmpty() && statusKind == null
            }
            val roleplayLiteralCode = indentedCode + inlineCode + literalFences.map { it.range } + cdata
            val ranges = (codeBlocks + inlineCode + comments + cdata).indexedRanges()
            val dialogue = if (interpretProseQuotes && ('[' in end || '［' in end)) {
                val closingPositions = familyTags.filter { it.value in closings }.map { it.range.first }
                roleplaySpokenDialogueRanges(end, ranges, includeUnclosed, closingPositions) { isEscaped(end, it) }
            } else emptyList()
            val quoteDelimitersToSkip = (ranges + familyTags.map { it.range }).indexedRanges()
            val interiorClosings = if (includeUnclosed) familyTags.mapIndexedNotNull { index, tag ->
                val position = tag.range.first
                val linePrefix = source.subSequence(lineStart(source, position), position)
                val nextTag = familyTags.getOrNull(index + 1)?.range?.first ?: end.length
                val continuationLines = end.subSequence(tag.range.last + 1, nextTag).lineSequence().iterator()
                position.takeIf {
                    tag.value in closings && linePrefix.isBlank() &&
                        !hasCodeIndent(source, position, indentedParagraphLineStarts) &&
                        !ranges.containsOffset(position) && continuationLines.next().isBlank() &&
                        continuationLines.hasNext() && hanIdeograph.containsMatchIn(continuationLines.next())
                }
            } else emptyList()
            val collectMarkup = { markup: LiteralMarkup ->
                pendingMarkup += markup
                !markupCanChangeMarkdown ||
                    canBatchMarkup(source, markup, containerLineStarts, lazyQuoteLineStarts, listItemLineStarts)
            }
            val quotes = proseQuoteRanges(
                end, source, quoteDelimitersToSkip, includeUnclosed, closings, interiorClosings,
                collectMarkup.takeIf { hasMarkup },
                interpretProseQuotes, dialogue,
            )
            return ReplyLiterals(
                ranges = (ranges + quotes + dialogue).indexedRanges(),
                roleplayLiteralCode = roleplayLiteralCode,
                indentedCode = indentedCode,
                lazyQuoteLineStarts = lazyQuoteLineStarts,
                listItemLineStarts = listItemLineStarts,
                lazyListItemLineStarts = lazyListItemLineStarts,
                indentedParagraphLineStarts = indentedParagraphLineStarts,
                markdown = source,
                commentRanges = comments,
                fences = fences,
            )
        }

        var result = inspect(markdown)
        while (pendingMarkup.isNotEmpty()) {
            for (markup in pendingMarkup) {
                if (markup.isComment) comments += markup.range else cdata += markup.range
            }
            pendingMarkup.clear()
            // A batch stops when masking a range can make Markdown reinterpret later text.
            result = inspect(maskLiteralMarkup(markdown, comments + cdata))
        }
        return result
    }

    private fun canBatchMarkup(
        text: String,
        markup: LiteralMarkup,
        containers: Set<Int>,
        lazyQuotes: Set<Int>,
        listItems: Set<Int>,
    ): Boolean {
        val range = markup.range
        val containsBackticks = range.any { text[it] == '`' }
        val multiline = range.any { text[it] == '\n' }
        // Backtick runs can only pair inside their paragraph. A standalone, isolated
        // one-line comment cannot affect a different paragraph when its runs are masked.
        if (containsBackticks && multiline) return false
        val start = lineStart(text, range.first)
        val prefix = text.subSequence(start, range.first)
        if (multiline || containsBackticks) {
            if (!markup.isComment || !prefix.isBlank() || start in containers || start in lazyQuotes) return false
            val end = text.indexOf('\n', range.last + 1).let { if (it < 0) text.length else it }
            if (!text.subSequence(range.last + 1, end).isBlank()) return false
            // Blank lines isolate a plain, standalone note from adjacent Markdown paragraphs.
            val blankBefore = start == 0 || text.subSequence(lineStart(text, start - 1), start - 1).isBlank()
            val nextEnd = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
            val blankAfter = end == text.length || text.subSequence(end + 1, nextEnd).isBlank()
            return blankBefore && blankAfter && !markdownContainerOrIndent.containsMatchIn(text.subSequence(start, end))
        }
        // A visible lead outside the Markdown block markers keeps the line in its paragraph.
        // A markup opener or a masking placeholder can disappear when another note is masked.
        val lead = prefix.trimStart().firstOrNull()
        if (lead != null && !lead.isDigit() && lead !in "`~>+-*_=#<\uFFFC") return true
        // Masking one inline note after visible list prose leaves its marker, indentation, and
        // paragraph state intact. Empty items and notes containing block or code syntax still rescan.
        if (markup.isComment && start in listItems &&
            MarkdownCodeFences.hasListProseBefore(text, start, range.first)
        ) return true
        if (!prefix.isBlank() || start in containers || start in lazyQuotes) return false
        val after = text.indexOf('\n', range.last + 1).let { if (it < 0) text.length else it }
        if (!text.subSequence(range.last + 1, after).isBlank()) return false
        // A blank line cannot change a following top-level prose line or a run of standalone notes.
        val next = text.getOrNull(after + 1)
        return next == null || next == '\n' || next.isLetter() ||
            text.startsWith(commentOpening, after + 1) || text.startsWith(cdataOpening, after + 1)
    }

    private fun List<IntRange>.indexedRanges(): List<IntRange> = buildList {
        for (range in this@indexedRanges.sortedBy { it.first }) {
            if (range.isEmpty()) continue
            val previous = lastOrNull()
            if (previous != null && range.first <= previous.last) {
                this[lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else add(range)
        }
    }

    private fun List<IntRange>.containsOffset(offset: Int): Boolean {
        val index = binarySearchBy(offset, selector = IntRange::first)
        return index >= 0 || -index - 2 >= 0 && offset <= this[-index - 2].last
    }

    private fun inlineCodeRangesOutsideBlocks(
        body: String,
        codeBlocks: List<IntRange>,
        indentedParagraphLineStarts: Set<Int>,
    ): List<IntRange> {
        if ('`' !in body) return emptyList()
        val boundaries = (codeBlocks + paragraphBreak.findAll(body).map { it.range }.toList())
            .sortedBy { it.first }
        return buildList {
            var start = 0
            for (boundary in boundaries) {
                if (start < boundary.first) {
                    addAll(inlineCodeRanges(body, start, boundary.first, indentedParagraphLineStarts))
                }
                start = maxOf(start, boundary.last + 1)
            }
            if (start < body.length) addAll(inlineCodeRanges(body, start, body.length, indentedParagraphLineStarts))
        }
    }

    private fun inlineCodeRanges(
        text: String,
        start: Int,
        end: Int,
        indentedParagraphLineStarts: Set<Int>,
    ): List<IntRange> = buildList {
        val runs = backtickRuns.findAll(text, start).takeWhile { it.range.first < end }.toList()
        var index = 0
        while (index < runs.size) {
            val opening = runs[index++]
            val position = opening.range.first
            if (hasCodeIndent(text, position, indentedParagraphLineStarts)) continue
            val unescapedStart = if (isEscaped(text, position)) position + 1 else position
            val length = opening.range.last + 1 - unescapedStart
            if (length == 0) continue
            // A span closes with the next run of equal length. Backslashes inside it are literal.
            val close = (index until runs.size).firstOrNull { runs[it].value.length == length } ?: continue
            add(unescapedStart..runs[close].range.last)
            index = close + 1
        }
    }

    private fun proseQuoteRanges(
        text: String,
        markdown: String,
        delimiterExclusions: List<IntRange>,
        includeUnclosed: Boolean,
        wrapperClosings: List<String>,
        interiorClosings: List<Int>,
        onMarkup: ((LiteralMarkup) -> Boolean)?,
        interpretProseQuotes: Boolean,
        dialogue: List<IntRange>,
    ): List<IntRange> = buildList {
        var open = -1
        var closing: Char? = null
        var depth = 0
        var canOpenComment = onMarkup != null
        var markupEnd = 0
        fun isProseDelimiter(index: Int): Boolean =
            !isEscaped(text, index) && !delimiterExclusions.containsOffset(index) && dialogue.none { index in it }

        for (index in text.indices) {
            if (index < markupEnd) continue
            if (canOpenComment && markdown.startsWith(commentOpening, index) &&
                !isEscaped(text, index) && !delimiterExclusions.containsOffset(index)
            ) {
                val end = closedCommentEnd(text, index)
                val inQuote = MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
                if (end != null || includeUnclosed && open < 0 && !inQuote) {
                    markupEnd = end ?: text.length
                    if (onMarkup?.invoke(LiteralMarkup(index until markupEnd, isComment = true)) != true) break
                    continue
                }
                // The body renderer treats the rest of a completed, unclosed comment as ordinary text.
                if (open < 0 && !inQuote) canOpenComment = false
            }
            if (onMarkup != null && open < 0 && markdown.startsWith(cdataOpening, index) && isProseDelimiter(index)) {
                val close = text.indexOf(cdataClosing, index + cdataOpening.length)
                val inQuote = MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
                if (close >= 0 || includeUnclosed && !inQuote) {
                    markupEnd = if (close >= 0) close + cdataClosing.length else text.length
                    if (!onMarkup(LiteralMarkup(index until markupEnd, isComment = false))) break
                    continue
                }
            }
            if (!interpretProseQuotes) continue
            val symbol = text[index]
            val matchingClose = proseDelimiters[symbol]
            if (matchingClose == null && symbol != closing) continue
            if (!isProseDelimiter(index)) continue
            val followsWord = text.getOrNull(index - 1)?.isLetterOrDigit() == true
            val precedesWord = text.getOrNull(index + 1)?.isLetterOrDigit() == true
            // Word apostrophes and possessives use the same boundaries for both quote styles.
            if ((symbol == '\'' || symbol == '’') && followsWord &&
                (precedesWord || open < 0 || symbol == closing && isQuotedPossessive(
                    text, open, index, wrapperClosings.takeIf { includeUnclosed }.orEmpty(), interiorClosings, ::isProseDelimiter,
                ))
            ) continue
            if (open < 0 && !followsWord && (symbol == '\'' || symbol == '‘') &&
                leadingApostropheContraction.matchesAt(text, index + 1) &&
                subsequentSingleQuoteCloses(text, index, symbol, ::isProseDelimiter) != true
            ) continue
            if (symbol == '"' && open < 0 && text.getOrNull(index - 1)?.isDigit() == true && !precedesWord) continue
            when {
                open < 0 && matchingClose != null -> {
                    open = index
                    closing = matchingClose
                    depth = 1
                }
                symbol == closing -> {
                    if (--depth == 0) {
                        add(open..index)
                        open = -1
                        closing = null
                    }
                }
                open >= 0 && symbol == text[open] && !isParagraphQuoteContinuation(markdown, index) -> depth++
            }
        }
        if (includeUnclosed && open >= 0) add(open..text.lastIndex)
    }

    private fun isParagraphQuoteContinuation(markdown: String, opening: Int): Boolean {
        // Conventional multi-paragraph English quotation repeats the opening mark and closes only once.
        if (markdown[opening] !in "“‘" || !hasMarkerPadding(markdown, opening)) return false
        val currentLine = lineStart(markdown, opening)
        val previous = previousVisibleLine(markdown, currentLine) ?: return false
        return paragraphBreak.containsMatchIn(markdown.subSequence(previous.last + 1, currentLine))
    }

    /** Null while there is no later delimiter. A new opening does not complete this quote. */
    private fun subsequentSingleQuoteCloses(
        text: String,
        after: Int,
        opening: Char,
        isProseDelimiter: (Int) -> Boolean,
    ): Boolean? {
        val closing = proseDelimiters.getValue(opening)
        val next = (after + 1 until text.length).firstOrNull { index ->
            (text[index] == opening || text[index] == closing) &&
                !isWordApostrophe(text, index) && isProseDelimiter(index)
        } ?: return null
        if (text[next] != closing) return false
        // For straight quotes, an isolated delimiter before a word can open a new quotation.
        val precedesWord = text.getOrNull(next + 1)?.isLetterOrDigit() == true
        val followsWord = text.getOrNull(next - 1)?.isLetterOrDigit() == true
        return opening != closing || !precedesWord || followsWord
    }

    private fun isQuotedPossessive(
        text: String,
        quoteStart: Int,
        position: Int,
        provisionalClosings: List<String>,
        interiorClosings: List<Int>,
        isProseDelimiter: (Int) -> Boolean,
    ): Boolean {
        if (!text[position - 1].equals('s', ignoreCase = true) ||
            text.getOrNull(position + 1)?.isWhitespace() != true
        ) return false
        val wordStart = (position + 2 until text.length).firstOrNull { !text[it].isWhitespace() } ?: return false
        if (!text[wordStart].isLetter()) return false
        subsequentSingleQuoteCloses(text, position, text[quoteStart], isProseDelimiter)?.let { return it }
        if (!unfinishedPluralPossessor.matches(text.subSequence(quoteStart + 1, position))) return false
        if (lineStart(text, wordStart) != lineStart(text, position)) return false

        if (hasMarkerPadding(text, quoteStart) && provisionalClosings.isNotEmpty()) {
            val leadEnd = lineEnd(text, wordStart)
            val introduction = possessedLiteralLineLead.matchEntire(text.subSequence(wordStart, leadEnd))
            val possessed = introduction?.groupValues?.get(1)?.lowercase()
            if (possessed != null && possessed !in ordinaryProseFollowers && !possessed.endsWith("ly")) {
                val markerLine = if (text.startsWith("\r\n", leadEnd)) leadEnd + 2 else leadEnd + 1
                if (markerLine < text.length) {
                    val markerEnd = lineEnd(text, markerLine)
                    val markerStart = (markerLine until markerEnd).firstOrNull { text[it] != ' ' } ?: markerEnd
                    val marker = text.substring(markerStart, markerEnd).trimEnd()
                    if (hasMarkerPadding(text, markerStart) && marker.startsWith("</") &&
                        provisionalClosings.any { it.startsWith(marker) }
                    ) return true
                }
            }
        }

        // A speech cue and an immediately continuing Han line also witness this provisional reading.
        if (!chineseSpeechLead.containsMatchIn(text.subSequence(lineStart(text, quoteStart), quoteStart))) return false
        val tagLine = interiorClosings.firstOrNull { it > wordStart }?.let { lineStart(text, it) } ?: return false
        if (tagLine <= wordStart) return false
        val possessed = text.substring(wordStart, tagLine).trimEnd()
        return unfinishedPossessedWord.matches(possessed) && possessed !in ordinaryProseFollowers && !possessed.endsWith("ly")
    }

    private fun isWordApostrophe(text: String, position: Int): Boolean =
        text.getOrNull(position - 1)?.isLetterOrDigit() == true &&
            text.getOrNull(position + 1)?.isLetterOrDigit() == true

    private fun maskLiteralMarkup(source: String, markup: List<IntRange>): String {
        val masked = source.toCharArray()
        val leadingMarkup = mutableMapOf<Int, IntRange>()
        for (range in markup.sortedBy { it.first }) {
            var start = lineStart(source, range.first)
            while (start <= range.last) {
                val end = source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
                val first = maxOf(start, range.first)
                val limit = minOf(end, range.last + 1)
                if (first < limit && (start until first).all { source[it] == ' ' || source[it] == '\t' }) {
                    leadingMarkup.putIfAbsent(start, first until end)
                }
                for (offset in first until limit) {
                    if (masked[offset] != '\r') masked[offset] = ' '
                }
                start = end + 1
            }
        }
        for (line in leadingMarkup.values) {
            // Preserve the original indentation of visible content on mixed lines. Markup-only
            // lines stay blank so they cannot turn the following indented code into a paragraph.
            if (line.any { !masked[it].isWhitespace() }) masked[line.first] = '\uFFFC'
        }
        return String(masked)
    }

    private fun closedCommentEnd(text: String, start: Int): Int? {
        var depth = 1
        for (token in commentDelimiters.findAll(text, start + commentOpening.length)) {
            if (token.value == commentOpening) depth++
            else if (--depth == 0) return token.range.last + 1
        }
        return null
    }

    private fun isEscaped(text: String, position: Int): Boolean {
        var preceding = position
        while (preceding > 0 && text[preceding - 1] == '\\') preceding--
        return (position - preceding) % 2 != 0
    }

    private fun lineStart(text: String, offset: Int): Int {
        var index = minOf(text.length, offset) - 1
        while (index >= 0 && !text[index].isHistoryLineBreak()) index--
        return index + 1
    }

    private fun lineEnd(text: String, offset: Int): Int =
        text.indexOfAny(charArrayOf('\r', '\n'), offset).takeIf { it >= 0 } ?: text.length

    private fun hasCodeIndent(text: String, offset: Int, indentedParagraphLineStarts: Set<Int> = emptySet()): Boolean {
        val lineStart = lineStart(text, offset)
        if (lineStart in indentedParagraphLineStarts) return false
        val contentStart = MarkdownCodeFences.afterQuotePrefixes(text, lineStart)
        val indent = text.substring(contentStart.coerceAtMost(offset), offset).takeWhile { it == ' ' || it == '\t' }
        return indent.length >= 4 || '\t' in indent
    }
}
