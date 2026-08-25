package com.example.chatbar.domain.image

import java.io.InputStream
import java.util.Locale

internal data class NovelAiPromptWordToken(
    val source: String,
    val normalized: String
)

class NovelAiPromptWordDictionary private constructor(
    private val bundledWords: Map<String, String>
) {
    internal fun tokens(source: String): List<NovelAiPromptWordToken> = WORD.findAll(source)
        .map { match ->
            NovelAiPromptWordToken(
                source = match.value,
                normalized = match.value.lowercase(Locale.ROOT)
            )
        }
        .toList()

    internal fun localTranslation(word: String): String? {
        val key = word.lowercase(Locale.ROOT)
        return WORDS[key] ?: bundledWords[key]
    }

    internal fun compose(source: String, translations: Map<String, String>): String {
        val normalizedSource = source.replace('_', ' ')
        val matches = WORD.findAll(normalizedSource).toList()
        if (matches.isEmpty()) return ""
        return buildString {
            var cursor = 0
            matches.forEach { match ->
                appendMeaningfulSeparator(normalizedSource.substring(cursor, match.range.first))
                val key = match.value.lowercase(Locale.ROOT)
                val translation = translations[key]
                if (translation != null) {
                    append(translation)
                } else {
                    if (isNotEmpty() && !last().isWhitespace()) append(' ')
                    append(match.value)
                    append(' ')
                }
                cursor = match.range.last + 1
            }
            appendMeaningfulSeparator(normalizedSource.substring(cursor))
        }.trim()
    }

    private fun StringBuilder.appendMeaningfulSeparator(separator: String) {
        separator.forEach { char ->
            when (char) {
                ',', '，' -> append('，')
                '.', '。' -> append('。')
                '!', '！' -> append('！')
                '?', '？' -> append('？')
                ':', '：' -> append('：')
                ';', '；' -> append('；')
                '/', '\\' -> append('/')
                '-', '–', '—' -> append('-')
                '\'', '’' -> Unit
            }
        }
    }

    companion object {
        fun fromTsv(input: InputStream): NovelAiPromptWordDictionary {
            val bundled = linkedMapOf<String, String>()
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank() || line.startsWith('#')) return@forEach
                    val separator = line.indexOf('\t')
                    if (separator <= 0 || separator == line.lastIndex) return@forEach
                    val word = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                    val translation = line.substring(separator + 1).trim()
                    if (word.matches(ASSET_WORD) && translation.any(::isHan)) {
                        bundled[word] = translation
                    }
                }
            }
            return NovelAiPromptWordDictionary(bundled)
        }

        private fun isHan(char: Char): Boolean =
            char.code in 0x3400..0x9FFF || char.code in 0xF900..0xFAFF

        private val WORD = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?")
        private val ASSET_WORD = Regex("[a-z]+(?:-[a-z]+)?")

        private val WORDS = mapOf(
        "a" to "一",
        "an" to "一",
        "the" to "该",
        "this" to "这",
        "that" to "那",
        "these" to "这些",
        "those" to "那些",
        "one" to "一",
        "two" to "二",
        "three" to "三",
        "and" to "与",
        "or" to "或",
        "but" to "但",
        "of" to "的",
        "in" to "在",
        "on" to "在",
        "at" to "在",
        "to" to "向",
        "from" to "从",
        "with" to "带有",
        "without" to "没有",
        "for" to "为了",
        "by" to "由",
        "near" to "靠近",
        "beside" to "旁边",
        "behind" to "后方",
        "under" to "下方",
        "below" to "下方",
        "above" to "上方",
        "over" to "上方",
        "inside" to "内部",
        "outside" to "外部",
        "through" to "穿过",
        "around" to "周围",
        "between" to "之间",
        "into" to "进入",
        "is" to "是",
        "are" to "是",
        "was" to "曾是",
        "were" to "曾是",
        "be" to "是",
        "being" to "正在",
        "has" to "有",
        "have" to "有",
        "having" to "有",
        "her" to "她的",
        "his" to "他的",
        "their" to "他们的",
        "she" to "她",
        "he" to "他",
        "they" to "他们",
        "girl" to "女孩",
        "girls" to "女孩",
        "boy" to "男孩",
        "boys" to "男孩",
        "woman" to "女性",
        "women" to "女性",
        "man" to "男性",
        "men" to "男性",
        "female" to "女性",
        "male" to "男性",
        "person" to "人物",
        "people" to "人群",
        "character" to "角色",
        "characters" to "角色",
        "young" to "年轻",
        "adult" to "成年",
        "child" to "儿童",
        "eyes" to "眼睛",
        "eye" to "眼睛",
        "hair" to "头发",
        "face" to "脸",
        "head" to "头部",
        "mouth" to "嘴",
        "lips" to "嘴唇",
        "hand" to "手",
        "hands" to "手",
        "arm" to "手臂",
        "arms" to "手臂",
        "leg" to "腿",
        "legs" to "腿",
        "foot" to "脚",
        "feet" to "脚",
        "body" to "身体",
        "skin" to "皮肤",
        "breast" to "乳房",
        "breasts" to "乳房",
        "standing" to "站立",
        "sitting" to "坐着",
        "walking" to "行走",
        "running" to "奔跑",
        "lying" to "躺着",
        "leaning" to "倚靠",
        "jumping" to "跳跃",
        "dancing" to "跳舞",
        "flying" to "飞行",
        "looking" to "看向",
        "facing" to "面向",
        "smiling" to "微笑",
        "crying" to "哭泣",
        "laughing" to "大笑",
        "holding" to "拿着",
        "taking" to "拍摄",
        "daring" to "大胆",
        "selfie" to "自拍",
        "pulling" to "拉开",
        "expose" to "露出",
        "while" to "同时",
        "bending" to "弯身",
        "forward" to "向前",
        "steam" to "蒸汽",
        "coming" to "飘出",
        "cold" to "寒冷",
        "winter" to "冬季",
        "air" to "空气",
        "public" to "公共",
        "wearing" to "穿着",
        "touching" to "触碰",
        "reaching" to "伸手",
        "eating" to "进食",
        "drinking" to "饮用",
        "reading" to "阅读",
        "writing" to "书写",
        "sleeping" to "睡眠",
        "open" to "敞开",
        "closed" to "闭合",
        "raised" to "抬起",
        "beautiful" to "美丽",
        "pretty" to "漂亮",
        "cute" to "可爱",
        "handsome" to "英俊",
        "happy" to "开心",
        "sad" to "悲伤",
        "angry" to "生气",
        "surprised" to "惊讶",
        "shy" to "害羞",
        "gentle" to "温柔",
        "calm" to "平静",
        "serene" to "宁静",
        "bright" to "明亮",
        "dark" to "昏暗",
        "soft" to "柔和",
        "hard" to "强烈",
        "warm" to "温暖",
        "cool" to "清凉",
        "small" to "小",
        "large" to "大",
        "big" to "大",
        "tall" to "高挑",
        "short" to "短",
        "long" to "长",
        "red" to "红色",
        "blue" to "蓝色",
        "green" to "绿色",
        "yellow" to "黄色",
        "orange" to "橙色",
        "purple" to "紫色",
        "pink" to "粉色",
        "black" to "黑色",
        "white" to "白色",
        "brown" to "棕色",
        "gray" to "灰色",
        "grey" to "灰色",
        "gold" to "金色",
        "silver" to "银色",
        "multicolored" to "多彩",
        "colorful" to "多彩",
        "clothes" to "衣服",
        "clothing" to "服装",
        "dress" to "连衣裙",
        "shirt" to "衬衫",
        "skirt" to "裙子",
        "coat" to "外套",
        "jacket" to "夹克",
        "uniform" to "制服",
        "shoes" to "鞋",
        "boots" to "靴子",
        "scarf" to "围巾",
        "hat" to "帽子",
        "naked" to "裸体",
        "nude" to "裸体",
        "room" to "房间",
        "bedroom" to "卧室",
        "library" to "图书馆",
        "classroom" to "教室",
        "school" to "学校",
        "street" to "街道",
        "city" to "城市",
        "forest" to "森林",
        "garden" to "花园",
        "park" to "公园",
        "beach" to "海滩",
        "ocean" to "海洋",
        "sea" to "海面",
        "sky" to "天空",
        "window" to "窗户",
        "table" to "桌子",
        "chair" to "椅子",
        "bed" to "床",
        "day" to "白天",
        "night" to "夜晚",
        "morning" to "清晨",
        "evening" to "傍晚",
        "sunset" to "日落",
        "background" to "背景",
        "foreground" to "前景",
        "light" to "光线",
        "lighting" to "光照",
        "shadow" to "阴影",
        "style" to "风格",
        "official" to "官方",
        "artist" to "艺术家",
        "collaboration" to "合作",
        "game" to "游戏",
        "cg" to "CG",
        "blurry" to "模糊",
        "upscaled" to "放大",
        "resized" to "调整尺寸",
        "lowres" to "低分辨率",
        "text" to "文字",
        "unfinished" to "未完成",
        "anime" to "动漫",
        "animation" to "动画",
        "coloring" to "上色",
        "shading" to "明暗",
        "screenshot" to "截图",
        "illustration" to "插画",
        "painting" to "绘画",
        "drawing" to "绘图",
        "photo" to "照片",
        "photograph" to "照片",
        "portrait" to "肖像",
        "realistic" to "写实",
        "cinematic" to "电影感",
        "dramatic" to "戏剧性",
        "dynamic" to "动态",
        "detailed" to "细致",
        "masterpiece" to "杰作",
        "quality" to "质量",
        "closeup" to "特写",
        "close" to "近距离",
        "full" to "全身",
        "medium" to "中景",
        "shot" to "镜头",
        "view" to "视角",
        "angle" to "角度",
        "front" to "正面",
        "side" to "侧面",
        "camera" to "镜头"
        )
    }
}
