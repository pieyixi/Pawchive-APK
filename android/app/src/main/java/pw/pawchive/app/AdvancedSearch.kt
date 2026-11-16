package pw.pawchive.app

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pw.pawchive.data.*
import java.time.LocalDate
import java.time.YearMonth

internal fun Modifier.quietClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
}

private val FilterPink = Color(0xFFFF6699)
@Composable private fun chipColor() = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF242429) else Color(0xFFF6F7F9)
private val centeredType = TextStyle(fontWeight = FontWeight.Normal, platformStyle = PlatformTextStyle(includeFontPadding = false))

/** Anchored to the bottom of the two home tabs, rather than a bottom sheet. */
@Composable
fun AdvancedSearchPanel(author: Boolean, filters: SearchFilters, panelWidth: Dp, panelHeight: Dp, dismiss: () -> Unit, apply: (SearchFilters) -> Unit) {
    var raw by rememberSaveable(author) { mutableStateOf(wireJson.encodeToString(filters)) }
    val draft = remember(raw) { wireJson.decodeFromString<SearchFilters>(raw) }
    fun update(value: SearchFilters) { raw = wireJson.encodeToString(value) }
    var dateField by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.width(panelWidth).height(panelHeight).testTag("advanced_search_overlay")) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .46f)).quietClick(onClick = dismiss))
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } }.testTag("advanced_search_panel")) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                    if (author) {
                        FilterGroup("排序方式", "sort", draft.sort, listOf("name" to "名称顺序", "updated" to "最近更新", "indexed" to "最近收录", "hot" to "收藏热度")) {
                            update(draft.copy(sort = it, order = if (it == "name") "asc" else "desc"))
                        }
                        FilterGroup("排列顺序", "order", draft.order, listOf("asc" to "升序", "desc" to "降序")) { update(draft.copy(order = it)) }
                    } else {
                        FilterGroup("网站收录顺序", "work_order", draft.workOrder, listOf("default" to "默认", "desc" to "降序", "asc" to "升序")) { update(draft.copy(workOrder = it)) }
                        FilterGroup("搜索范围", "range", draft.range, listOf("all" to "标题和正文", "title" to "仅标题")) { update(draft.copy(range = it)) }
                        FilterGroup("发布时间", "period", draft.period, listOf("all" to "不限", "day" to "最近一天", "week" to "最近一周", "half" to "最近半年")) { update(draft.period(it)) }
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            val today = LocalDate.now()
                            DateField(draft.start.ifEmpty { today.withDayOfMonth(1).toString() }, draft.period == "all", "选择开始日期", Modifier.weight(1f)) { dateField = "start" }
                            Text("至", Modifier.width(24.dp), textAlign = TextAlign.Center, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DateField(draft.end.ifEmpty { today.toString() }, draft.period == "all", "选择结束日期", Modifier.weight(1f)) { dateField = "end" }
                        }
                    }
                    FilterGroup("来源平台", "service", draft.service, listOf("all" to "全部", "patreon" to "Patreon", "fanbox" to "Fanbox") + if (author) listOf("discord" to "Discord") else emptyList()) { update(draft.copy(service = it)) }
                    if (!author) FilterGroup("附件类型", "media", draft.media, listOf("all" to "不限", "image" to "图片", "video" to "视频", "archive" to "压缩包", "audio" to "音频")) { update(draft.copy(media = it)) }
                    FilterGroup("AI 内容", "ai", draft.ai, listOf("all" to "全部", "hide" to "隐藏 AI", "only" to "只看 AI")) { update(draft.copy(ai = it)) }
                    if (!draft.validDates) Text("起始日期不能晚于结束日期", fontSize = 11.sp, color = Pink, modifier = Modifier.padding(top = 8.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlatAction("重置", Modifier.weight(1f).testTag("filter_reset"), chipColor(), MaterialTheme.colorScheme.onSurfaceVariant) { update(SearchFilters()) }
                    FlatAction("完成", Modifier.weight(1f).testTag("filter_apply"), Pink, Color(0xFF29121C), draft.validDates) { apply(draft) }
                }
            }
    }
    dateField?.let { field ->
        val today = LocalDate.now()
        val initial = LocalDate.parse((if (field == "start") draft.start else draft.end).ifEmpty { if (field == "start") today.withDayOfMonth(1).toString() else today.toString() })
        val min = if (field == "end" && draft.start.isNotEmpty()) LocalDate.parse(draft.start) else LocalDate.of(1900, 1, 1)
        val max = if (field == "start" && draft.end.isNotEmpty()) LocalDate.parse(draft.end) else today
        ReferenceDatePicker(initial, min, max, { dateField = null }) { selected ->
            update(if (field == "start") draft.copy(start = selected.toString(), period = "custom") else draft.copy(end = selected.toString(), period = "custom"))
            dateField = null
        }
    }
}

@Composable private fun FilterHeading(label: String) {
    Text(label, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}
@Composable private fun FilterGroup(label: String, key: String, selected: String, values: List<Pair<String, String>>, select: (String) -> Unit) {
    FilterHeading(label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (value, text) ->
                    Box(Modifier.weight(1f).height(36.dp).testTag("filter_${key}_$value").semantics { this.selected = selected == value; role = Role.RadioButton }
                        .quietClick { select(value) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(26.2.dp).clip(RoundedCornerShape(1.6.dp)).background(chipColor()), contentAlignment = Alignment.Center) {
                            Text(text, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, textAlign = TextAlign.Center, style = centeredType,
                                color = if (selected == value) Pink else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
@Composable private fun DateField(value: String, placeholder: Boolean, label: String, modifier: Modifier, click: () -> Unit) {
    val date = LocalDate.parse(value)
    Box(modifier.height(36.dp).semantics { contentDescription = label }.quietClick(onClick = click), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(26.2.dp).clip(RoundedCornerShape(1.6.dp)).background(chipColor()), contentAlignment = Alignment.Center) {
            Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日", fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, style = centeredType,
                color = if (placeholder) Color(0xFFC5C5C8) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun FlatAction(text: String, modifier: Modifier, background: Color, color: Color, enabled: Boolean = true, click: () -> Unit) {
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(1.6.dp)).background(background).quietClick(enabled = enabled, onClick = click), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, lineHeight = 20.sp, style = centeredType, color = color.copy(alpha = if (enabled) 1f else .4f))
    }
}

/** Reference dimensions normalized from the supplied 864px Bilibili capture to 393dp. */
@Composable
fun ReferenceDatePicker(initial: LocalDate, min: LocalDate, max: LocalDate, dismiss: () -> Unit, confirm: (LocalDate) -> Unit) {
    var selectedText by rememberSaveable { mutableStateOf(initial.coerceIn(min, max).toString()) }
    val selected = LocalDate.parse(selectedText)
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val month = YearMonth.parse(monthText)
    var years by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = minOf(493.dp, maxHeight)).background(MaterialTheme.colorScheme.background).testTag("reference_date_picker")) {
                Column(Modifier.fillMaxWidth().height(101.dp).background(FilterPink).padding(start = 24.dp, top = 15.dp, end = 12.dp)) {
                    Text("${selected.year}年", fontSize = 16.sp, lineHeight = 24.sp, color = Color.White.copy(alpha = .7f), modifier = Modifier.quietClick { years = !years }.testTag("calendar_year"))
                    Text("${selected.monthValue}月${selected.dayOfMonth}日周${"一二三四五六日"[selected.dayOfWeek.value - 1]}", fontSize = 36.sp, lineHeight = 48.sp, style = centeredType, color = Color.White, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
                if (years) {
                    val yearList = rememberLazyListState(initialFirstVisibleItemIndex = (selected.year - min.year - 3).coerceAtLeast(0))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 336.dp).weight(1f, fill = false).testTag("calendar_years"), state = yearList) {
                        items((min.year..max.year).toList()) { year ->
                            Box(Modifier.fillMaxWidth().height(48.dp).quietClick {
                                val next = selected.withYear(year).coerceIn(min, max); selectedText = next.toString(); monthText = YearMonth.from(next).toString(); years = false
                            }, contentAlignment = Alignment.Center) { Text("${year}年", fontSize = if (year == selected.year) 24.sp else 17.sp, color = if (year == selected.year) FilterPink else MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                } else Column(Modifier.heightIn(max = 336.dp).weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        val prev = month.minusMonths(1)
                        val next = month.plusMonths(1)
                        Box(Modifier.width(40.dp)) { if (prev.atEndOfMonth() >= min) Box(Modifier.size(40.dp).quietClick { monthText = prev.toString() }, contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上个月", Modifier.size(24.dp)) } }
                        Text("${month.year}年${month.monthValue}月", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 16.sp, style = centeredType)
                        Box(Modifier.width(40.dp)) { if (next.atDay(1) <= max) Box(Modifier.size(40.dp).quietClick { monthText = next.toString() }, contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下个月", Modifier.size(24.dp)) } }
                    }
                    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        "一二三四五六日".forEach { Text(it.toString(), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, style = centeredType) }
                    }
                    val offset = month.atDay(1).dayOfWeek.value - 1
                    repeat(6) { row ->
                        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp)) {
                            repeat(7) { column ->
                                val day = row * 7 + column - offset + 1
                                if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(40.dp)) else {
                                    val date = month.atDay(day); val enabled = date in min..max; val active = date == selected
                                    Box(Modifier.weight(1f).height(40.dp).testTag("calendar_$date").semantics { this.selected = active; contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日" }
                                        .quietClick(enabled = enabled) { selectedText = date.toString() }, contentAlignment = Alignment.Center) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).then(if (active) Modifier.background(FilterPink) else Modifier), contentAlignment = Alignment.Center) {
                                            Text(day.toString(), fontSize = 13.sp, style = centeredType, color = when { active -> Color.White; !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .3f); date == LocalDate.now() -> Color(0xFFCE7B98); else -> MaterialTheme.colorScheme.onSurface })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().height(55.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().quietClick(onClick = dismiss).testTag("calendar_cancel"), contentAlignment = Alignment.Center) { Text("取消", fontSize = 16.sp, style = centeredType) }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxHeight().quietClick { confirm(selected) }.testTag("calendar_confirm"), contentAlignment = Alignment.Center) { Text("确定", fontSize = 16.sp, style = centeredType) }
                }
            }
        }
    }
}
