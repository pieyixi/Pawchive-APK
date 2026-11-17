package pw.pawchive.media

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

private val menuPink=Color(0xFFFF6699)
private val rates=listOf(2f,1.5f,1.25f,1f,.75f,.5f)
private fun rateText(rate:Float)=when(rate) { 2f->"2.0";1f->"1.0";.5f->"0.5";else->rate.toString() }
@Composable private fun PlainOption(text:String,selected:Boolean=false,color:Color,onClick:()->Unit,modifier:Modifier=Modifier,fontSize:TextUnit=14.sp) {
    Box(modifier.clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,onClick=onClick),contentAlignment=Alignment.Center) {
        if(text=="稍后再看" || text=="下载文件") Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) { VideoGlyph(if(text=="稍后再看") "later" else "download",Modifier.size(22.dp),color);Spacer(Modifier.width(10.dp));Text(text,color=color,fontSize=16.sp) }
        else Text(text,color=if(selected) menuPink else color,fontSize=fontSize,maxLines=1,textAlign=TextAlign.Center)
    }
}

/** Three separate surfaces measured on Bilibili: portrait sheet, landscape more, speed rail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VideoMenus(panel:String?,full:Boolean,speed:Float,loop:Boolean,dismiss:()->Unit,selectRate:(Float)->Unit,selectLoop:(Boolean)->Unit,onLater:()->Unit,onDownload:()->Unit,onCopy:()->Unit) {
    if(panel==null)return
    if(!full) {
        ModalBottomSheet(onDismissRequest=dismiss,containerColor=Color(0xFFF6F7F9),contentColor=Color(0xFF18191C),shape=RoundedCornerShape(topStart=12.dp,topEnd=12.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth().height(80.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(64.dp).clickable {onCopy();dismiss()},horizontalAlignment=Alignment.CenterHorizontally) { Box(Modifier.size(44.dp).background(Color(0xFFF3F3F3),RoundedCornerShape(22.dp)),contentAlignment=Alignment.Center) {VideoGlyph("copy",Modifier.size(22.dp),Color(0xFF757575))};Text("复制链接",Modifier.padding(top=8.dp),fontSize=12.sp,color=Color(0xFF9499A0)) }
                }
                Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(10.dp))) {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        VideoGlyph("rate",Modifier.size(20.dp),Color.Black);Spacer(Modifier.width(10.dp));Text("倍速",Modifier.width(52.dp),fontSize=16.sp)
                        rates.reversed().forEach { rate -> PlainOption(rateText(rate),speed==rate,Color(0xFF61666D),{selectRate(rate)},Modifier.weight(1f).fillMaxHeight()) }
                    }
                    HorizontalDivider(Modifier.padding(start=44.dp),color=Color(0xFFEBEBED))
                    PlainOption("稍后再看",color=Color(0xFF18191C),onClick={onLater();dismiss()},modifier=Modifier.fillMaxWidth().height(52.dp))
                    HorizontalDivider(Modifier.padding(start=44.dp),color=Color(0xFFEBEBED))
                    PlainOption("下载文件",color=Color(0xFF18191C),onClick={onDownload();dismiss()},modifier=Modifier.fillMaxWidth().height(52.dp))
                }
                Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(10.dp))) {
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("循环播放",Modifier.weight(1f),fontSize=16.sp)
                        PlainOption("单集循环",loop,Color(0xFF61666D),{selectLoop(true)},Modifier.width(84.dp).fillMaxHeight())
                        PlainOption("不循环",!loop,Color(0xFF61666D),{selectLoop(false)},Modifier.width(76.dp).fillMaxHeight())
                    }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.45f)).clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,onClick=dismiss))
        if(panel=="speed") Column(Modifier.fillMaxHeight().width(138.dp).background(Color.Black.copy(alpha=.8f)).padding(vertical=32.dp)) {
            rates.forEach { rate -> PlainOption(rateText(rate)+"X",speed==rate,Color.White,{selectRate(rate);dismiss()},Modifier.fillMaxWidth().weight(1f),fontSize=18.sp) }
        } else Column(Modifier.width(360.dp).fillMaxHeight().background(Color.Black.copy(alpha=.65f)).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    listOf("稍后再看" to onLater,"下载文件" to onDownload,"复制链接" to onCopy).forEach { (label,action) -> Column(Modifier.width(80.dp).clickable {action();dismiss()},horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.size(50.dp).background(Color(0xFF24272B).copy(alpha=.9f),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) { VideoGlyph(when(label){"稍后再看"->"later";"下载文件"->"download";else->"copy"},Modifier.size(26.dp)) };Text(label,Modifier.padding(top=8.dp),color=Color(0xFF9499A0),fontSize=12.sp)
                } }
            }
            Column(Modifier.fillMaxWidth().background(Color(0xFF24272B).copy(alpha=.9f),RoundedCornerShape(10.dp))) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("循环播放",Modifier.weight(1f),color=Color.White,fontSize=16.sp)
                    PlainOption("单集循环",loop,Color(0xFF9499A0),{selectLoop(true)},Modifier.width(84.dp).fillMaxHeight())
                    PlainOption("不循环",!loop,Color(0xFF9499A0),{selectLoop(false)},Modifier.width(70.dp).fillMaxHeight())
                }
            }
        }
    }
}

