package pw.pawchive.data

import org.jsoup.Jsoup
import kotlinx.serialization.Serializable

val Post.novelCandidate: Boolean get() = images.size == 1 && videos.isEmpty() && files.none { it.extension in setOf("mp3", "wav", "ogg", "m4a") }

/** Conservative text heuristic, not an author block or a calibrated classifier. */
fun Post.isSuspectedNovel(): Boolean {
    if (!novelCandidate) return false
    val doc = Jsoup.parse(content)
    doc.select("script,style,nav").remove()
    if (doc.select("img").size > 1) return false
    val text = doc.wholeText()
    if (text.count { !it.isWhitespace() } < 6000) return false
    if (Regex("漫画|マンガ|イラスト集|画集|\\b(?:comics?|gallery|illustrations?)\\b|\\d+\\s*(?:枚|images|pages)", RegexOption.IGNORE_CASE).containsMatchIn(title)) return false
    if (Regex("お知らせ|ご報告|活動報告|投票|公告|使用说明|更新日志|\\b(?:changelog|devlog)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) return false
    val marker = Regex("小[説说說]|第[\\d０-９一二三四五六七八九十百千]+[章話话回]|\\b(?:chapter|novel|oneshot)\\b|\\bch\\.?\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(title + " " + text.take(800))
    val quotes = text.count { it in "「」『』“”\"" }
    val longParagraphs = doc.select("p").count { it.text().length >= 120 }
    return marker || quotes >= 12 || longParagraphs >= 8
}

@Serializable
data class NovelVerdict(val suspect: Boolean, val checkedAt: Long, val revision: String)
