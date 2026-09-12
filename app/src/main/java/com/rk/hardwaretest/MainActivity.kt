package com.rk.hardwaretest

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbManager
import android.util.Range
import android.util.Size
import android.media.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rk.hardwaretest.model.*
import com.rk.hardwaretest.camera.Camera2PreviewController
import com.rk.hardwaretest.camera.CameraPreviewMetrics
import com.rk.hardwaretest.camera.LatestMetricsCallback
import com.rk.hardwaretest.camera.CameraRequestConfig
import com.rk.hardwaretest.camera.CameraSceneMode
import com.rk.hardwaretest.camera.CameraSceneProfile
import com.rk.hardwaretest.camera.assessRecordedFrames
import com.rk.hardwaretest.camera.assessSceneAnomalies
import com.rk.hardwaretest.camera.assessSpatialOcclusion
import com.rk.hardwaretest.camera.assessSpatialBrightHotspot
import com.rk.hardwaretest.camera.AiFeatureResult
import com.rk.hardwaretest.camera.AiFrameFeature
import com.rk.hardwaretest.camera.AiFrameFeatureExtractor
import com.rk.hardwaretest.camera.BaselineCapturePhase
import com.rk.hardwaretest.camera.baselineCaptureLabel
import com.rk.hardwaretest.camera.bitmapFrameSample
import com.rk.hardwaretest.camera.bitmapAiInput
import com.rk.hardwaretest.camera.buildSceneBaseline
import com.rk.hardwaretest.camera.colorCastReason
import com.rk.hardwaretest.camera.combineQualityReasons
import com.rk.hardwaretest.camera.hasRelativeOverexposureStatistics
import com.rk.hardwaretest.camera.isUsableBaselineFrame
import com.rk.hardwaretest.camera.lightBand
import com.rk.hardwaretest.camera.loadBaseline
import com.rk.hardwaretest.camera.loadCameraSceneProfile
import com.rk.hardwaretest.camera.loadStoredBaseline
import com.rk.hardwaretest.camera.NormalizedRoi
import com.rk.hardwaretest.camera.normalizedRoiFromDrag
import com.rk.hardwaretest.camera.RknnFeatureExtractor
import com.rk.hardwaretest.camera.recordingAnalysisTimesUs
import com.rk.hardwaretest.camera.RelativeOverexposureDiagnostic
import com.rk.hardwaretest.camera.relativeOverexposureDiagnostic
import com.rk.hardwaretest.camera.relativeOverexposureReason
import com.rk.hardwaretest.camera.saveBaseline
import com.rk.hardwaretest.camera.saveCameraSceneProfile
import com.rk.hardwaretest.camera.saveStoredBaseline
import com.rk.hardwaretest.camera.SceneBaseline
import com.rk.hardwaretest.camera.spatialSignature
import com.rk.hardwaretest.camera.StoredSceneBaseline
import com.rk.hardwaretest.camera.baselineMatchesRoi
import com.rk.hardwaretest.camera.canStartCameraDetection
import com.rk.hardwaretest.camera.cropArgb
import com.rk.hardwaretest.camera.isValid
import com.rk.hardwaretest.camera.sceneActionState
import com.rk.hardwaretest.camera.summaryLines
import com.rk.hardwaretest.camera.shouldRunFixedSceneModel
import com.rk.hardwaretest.camera.UnavailableFeatureExtractor
import com.rk.hardwaretest.camera.videoSampleTimesUs
import com.rk.hardwaretest.interfaces.GpioLevel
import com.rk.hardwaretest.interfaces.GpioReading
import com.rk.hardwaretest.interfaces.parseGpioLevel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { HardwareScreen(this) } }
}

private enum class CameraQualityPhase { IDLE, PREPARING, RECORDING, ANALYSING }
private data class RecordedVideoAnalysis(
    val assessment: com.rk.hardwaretest.camera.RecordedQualityAssessment,
    val frameCount: Int,
    val aiReasons: List<String> = emptyList(),
    val aiStatus: String = "未建立基线，已跳过 AI 检测",
    val aiScore: Float? = null,
    val aiBands: String = "-",
    val relativeOverexposureDiagnostic: RelativeOverexposureDiagnostic? = null,
    val localHotspotPercent: Float? = null,
)

private sealed interface BaselineCreationResult {
    data class Created(val baseline: SceneBaseline, val acceptedFrames: Int) : BaselineCreationResult
    data class Failed(val reason: String) : BaselineCreationResult
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HardwareScreen(context: Context) {
 var results by remember { mutableStateOf<Map<String, TestResult>>(emptyMap()) }
 var page by remember { mutableStateOf("一键测试") }
 var expanded by remember { mutableStateOf<String?>(null) }
 val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ }
 MaterialTheme { Column(Modifier.fillMaxSize().padding(10.dp)) {
  Row(Modifier.fillMaxWidth().weight(.2f), horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("一键测试","摄像头测试","扬声器测试","麦克风测试","网络测试","接口测试").forEach { title -> Card(modifier=Modifier.weight(1f).fillMaxHeight().clickable{page=title},border=if(page==title) BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(title+"\n"+if(page==title)"当前页面" else "查看结果",textAlign=TextAlign.Center)}} } }
  Box(Modifier.fillMaxWidth().weight(.8f).padding(top=8.dp)) { when(page){"摄像头测试"->CameraPage(context,results["camera"]){value->results=results+value};"扬声器测试"->SpeakerPage(context,results["speaker"]){value->results=results+value};"麦克风测试"->MicrophonePage(context,results["microphone"]){device->results=results+microphoneTest(context,device)};"网络测试"->NetworkPage(context,results["network"]){value->results=results+value};"接口测试"->InterfacePage(context);else->OneClickPage(context,results){results=results+cameraTest(context)+speakerTest(context)+microphoneTest(context,null)+networkTest(context)} } }
 } }
}
@Composable private fun SinglePage(name:String,result:TestResult?,retry:()->Unit){Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(name,style=MaterialTheme.typography.headlineSmall);ResultCard(name,result);Button(onClick=retry){Text("重新测试")}}}
@Composable private fun OneClickPage(context:Context,results:Map<String,TestResult>,runAll:()->Unit){
    val camera=context.getSystemService(CameraManager::class.java); val audio=context.getSystemService(AudioManager::class.java); val usb=context.getSystemService(UsbManager::class.java); val network=networkTest(context).second
    val gpio=listOf("io1","io2","io3","io4").joinToString(" · "){name->"$name="+runCatching{File("/sys/class/gpio_sw/$name/data").readText().trim()}.getOrDefault("?")}
    val outputs=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString{it.productName.toString()}.ifEmpty{"无"}
    val inputDevices=audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    val inputs=inputDevices.joinToString{it.productName.toString()}.ifEmpty{"无"}
    val microphoneDetails=inputDevices.mapIndexed{index,device->"${index+1}. ${device.productName} · ID ${device.id} · ${com.rk.hardwaretest.audio.inputDeviceTypeLabel(device.type)} · 声道 ${device.channelCounts.joinToString().ifEmpty{"系统默认"}} · 采样率 ${device.sampleRates.joinToString().ifEmpty{"系统默认"}}"}.joinToString("\n").ifEmpty{"未发现麦克风"}
    val videoCount=File("/dev").listFiles()?.count{it.name.startsWith("video")}?:0
    val usbNames=usb.deviceList.values.joinToString("\n"){device->"${device.productName?:device.deviceName} · VID:${device.vendorId.toString(16)} PID:${device.productId.toString(16)} · 接口 ${device.interfaceCount} 个"}.ifEmpty{"无"}
    fun status(key:String):String = results[key]?.status?.name?:"未测试"
    fun details(key:String):String = results[key]?.details?.entries?.joinToString(" · ") { entry -> "${entry.key}:${entry.value}" }.orEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("一键测试总览",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));Button(onClick=runAll){Text("执行全部测试")}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            Card(Modifier.weight(1f)){Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text("摄像头 · ${status("camera")}",style=MaterialTheme.typography.titleSmall);Text("已识别：${camera.cameraIdList.size} 路");Text(results["camera"]?.summary?:"尚未测试",style=MaterialTheme.typography.bodySmall);Text(details("camera"),style=MaterialTheme.typography.bodySmall)}}
            Card(Modifier.weight(1f)){Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text("扬声器 · ${status("speaker")}",style=MaterialTheme.typography.titleSmall);Text("音量：${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}");Text("输出：$outputs",style=MaterialTheme.typography.bodySmall);Text(results["speaker"]?.summary?:"尚未测试",style=MaterialTheme.typography.bodySmall);Text(details("speaker"),style=MaterialTheme.typography.bodySmall)}}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            Card(Modifier.weight(1f)){Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text("麦克风 · ${status("microphone")} · ${inputDevices.size} 个",style=MaterialTheme.typography.titleSmall);Text("输入：$inputs",style=MaterialTheme.typography.bodySmall);Text(microphoneDetails,style=MaterialTheme.typography.bodySmall);Text(results["microphone"]?.summary?:"尚未测试",style=MaterialTheme.typography.bodySmall);Text(details("microphone"),style=MaterialTheme.typography.bodySmall)}}
            Card(Modifier.weight(1f)){Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text("网络 · ${status("network")}",style=MaterialTheme.typography.titleSmall);Text(network.summary,style=MaterialTheme.typography.bodySmall);Text(network.details.entries.joinToString(" · "){"${it.key}:${it.value}"},style=MaterialTheme.typography.bodySmall);Text(details("network"),style=MaterialTheme.typography.bodySmall)}}
        }
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text("接口",style=MaterialTheme.typography.titleSmall);Text("GPIO：$gpio");Text("USB：${usb.deviceList.size} 个已识别外设\n$usbNames",style=MaterialTheme.typography.bodySmall);Text("视频节点：$videoCount 个",style=MaterialTheme.typography.bodySmall)}}
    }
}
@Composable private fun InterfacePage(context:Context){val gpioNames=remember{listOf("io1" to "GPIO 54","io2" to "GPIO 40","io3" to "GPIO 45","io4" to "GPIO 44")};var readings by remember{mutableStateOf<Map<String,GpioReading>>(emptyMap())};var updated by remember{mutableStateOf("等待读取")};val usb=context.getSystemService(UsbManager::class.java);val audio=context.getSystemService(AudioManager::class.java);val camera=context.getSystemService(CameraManager::class.java);LaunchedEffect(Unit){var previous=emptyMap<String,GpioReading>();while(isActive){val current=withContext(Dispatchers.IO){gpioNames.associate{(name,_)->val level=runCatching{parseGpioLevel(File("/sys/class/gpio_sw/$name/data").readText())}.getOrDefault(GpioLevel.UNAVAILABLE);name to GpioReading(level,previous[name]?.level!=null&&previous[name]?.level!=level)}};readings=current;previous=current;updated="已刷新 ${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date())}";delay(300)}};val usbText=usb.deviceList.values.joinToString("\n"){device->val classes=(0 until device.interfaceCount).map{device.getInterface(it).interfaceClass};val use=classes.firstOrNull{it in setOf(1,2,3,8,9,10,14)}?.let{com.rk.hardwaretest.interfaces.usbUsageLabel(it)}?:"通用 USB 外设";"已使用 · $use\n${device.productName?:device.deviceName} · VID:${device.vendorId.toString(16)} PID:${device.productId.toString(16)}"}.ifEmpty{"未发现已连接的 USB 外设"};val audioInputs=audio.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString{it.productName.toString()}.ifEmpty{"无"};val audioOutputs=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString{it.productName.toString()}.ifEmpty{"无"};val videoNodes=File("/dev").listFiles()?.filter{it.name.startsWith("video")}?.joinToString{it.name}?:"不可读取";Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("接口测试",style=MaterialTheme.typography.headlineSmall);Text("GPIO 实时状态（300 ms 刷新）· $updated");gpioNames.forEach{(name,label)->val item=readings[name];Text("$name · $label：${item?.level?.label?:"读取中"}${if(item?.changed==true)"  ← 状态已变化" else ""}",color=if(item?.changed==true)Color(0xFFFF9800) else Color.Unspecified)};Text("USB 当前使用状态：\n$usbText");Text("音频输入：$audioInputs");Text("音频输出：$audioOutputs");Text("摄像头/视频输入：${camera.cameraIdList.size} 路摄像头；/dev/$videoNodes");Text("网络接口：${NetworkInterface.getNetworkInterfaces().toList().joinToString{it.name}.ifEmpty{"无"}}");Text("说明：USB 显示的是内核已识别和绑定的外设用途；Android 不向普通应用提供其他 App 的独占占用归属。GPIO 仅只读监测，不会改变任何电平。")}}
@Composable private fun NetworkPage(context:Context,result:TestResult?,update:(Pair<String,TestResult>)->Unit){var current by remember{mutableStateOf(networkTest(context).second)};var transfer by remember{mutableStateOf("接收：等待采样 · 传输：等待采样")};var speed by remember{mutableStateOf("未测速")};var testing by remember{mutableStateOf(false)};val scope=rememberCoroutineScope();LaunchedEffect(Unit){var rx=TrafficStats.getTotalRxBytes();var tx=TrafficStats.getTotalTxBytes();var time=System.currentTimeMillis();while(isActive){delay(1000);val now=System.currentTimeMillis();val newRx=TrafficStats.getTotalRxBytes();val newTx=TrafficStats.getTotalTxBytes();transfer="实时接收：${com.rk.hardwaretest.network.formatBitsPerSecond(com.rk.hardwaretest.network.bytesPerSecond(rx,newRx,now-time)*8)} · 实时传输：${com.rk.hardwaretest.network.formatBitsPerSecond(com.rk.hardwaretest.network.bytesPerSecond(tx,newTx,now-time)*8)}";rx=newRx;tx=newTx;time=now}};Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("网络测试",style=MaterialTheme.typography.headlineSmall);ResultCard("当前网络信息",current);Text(transfer);Text("测速节点：公开 CDN 就近节点（非固定城市节点）");Button(enabled=!testing,onClick={scope.launch{testing=true;speed=withContext(Dispatchers.IO){publicDownloadSpeedTest()};testing=false}}){Text(if(testing)"测速中…" else "开始测速")};Text("测速结果：$speed");Button(onClick={val value=networkTest(context);current=value.second;update(value)}){Text("刷新网络信息")};ResultCard("最近一次网络测试",result)}}
@Composable private fun SpeakerPage(context:Context,result:TestResult?,update:(Pair<String,TestResult>)->Unit){val audio=context.getSystemService(AudioManager::class.java);val maxVolume=remember{audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)};var volumePercent by remember{mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)*100f/maxVolume)};var hz by remember{mutableFloatStateOf(440f)};var seconds by remember{mutableFloatStateOf(2f)};var amplitude by remember{mutableFloatStateOf(.25f)};var player by remember{mutableStateOf<MediaPlayer?>(null)};var progress by remember{mutableFloatStateOf(0f)};LaunchedEffect(Unit){while(isActive){val active=player;if(active!=null&&com.rk.hardwaretest.audio.shouldRefreshPlaybackProgress(active.isPlaying)){progress=active.currentPosition.toFloat()};delay(200)}};val applyVolume:(Float)->Unit={value->volumePercent=value;audio.setStreamVolume(AudioManager.STREAM_MUSIC,com.rk.hardwaretest.audio.speakerVolumeIndex(value,maxVolume),0);player?.setVolume(value/100f,value/100f);Unit};val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){player?.release();player=MediaPlayer.create(context,uri)?.also{it.setVolume(volumePercent/100f,volumePercent/100f)};progress=0f;player?.setOnCompletionListener{progress=it.duration.toFloat()};update("speaker" to TestResult(TestStatus.PASS,"已载入导入音频",mapOf("文件" to uri.lastPathSegment.orEmpty(),"时长" to "${(player?.duration?:0)/1000.0} 秒","实际输出设备" to (player?.routedDevice?.productName?.toString()?:"系统默认"))))}};DisposableEffect(Unit){onDispose{player?.release()}};Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("扬声器测试",style=MaterialTheme.typography.headlineSmall);Text("媒体音量：${volumePercent.roundToInt()}%（系统音量 ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/$maxVolume）");Slider(value=volumePercent,onValueChange=applyVolume,valueRange=0f..100f);Text("频率：${hz.roundToInt()} Hz");Slider(value=hz,onValueChange={hz=it},valueRange=80f..4000f);Text("时长：${"%.1f".format(seconds)} 秒");Slider(value=seconds,onValueChange={seconds=it},valueRange=.5f..10f);Text("PCM 振幅：${(amplitude*100).roundToInt()}%");Slider(value=amplitude,onValueChange={amplitude=it},valueRange=.05f..1f);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={update(speakerTest(context,hz.roundToInt(),seconds,amplitude))}){Text("播放测试音")};Button(onClick={picker.launch(arrayOf("audio/*"))}){Text("导入音频")}};if(player!=null){Text("导入音频：${(progress/1000).roundToInt()} / ${(player!!.duration/1000).coerceAtLeast(0)} 秒");Slider(value=progress,onValueChange={value->progress=value;player?.seekTo(value.toInt())},valueRange=0f..player!!.duration.toFloat());Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={player?.start()}){Text("播放")};Button(onClick={player?.pause()}){Text("暂停")}}};ResultCard("最近一次测试",result)}}
@Composable private fun MicrophonePage(context:Context,result:TestResult?,retry:(AudioDeviceInfo?)->Unit){val audio=context.getSystemService(AudioManager::class.java);val devices=remember{audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()};var index by remember{mutableIntStateOf(0)};var monitoring by remember{mutableStateOf(true)};var peakDb by remember{mutableFloatStateOf(-80f)};var rmsDb by remember{mutableFloatStateOf(-80f)};var health by remember{mutableStateOf(com.rk.hardwaretest.audio.MicrophoneHealth.WAITING)};var routedName by remember{mutableStateOf("未连接")};val selected=devices.getOrNull(index);LaunchedEffect(monitoring,selected?.id){peakDb=-80f;rmsDb=-80f;health=if(monitoring)com.rk.hardwaretest.audio.MicrophoneHealth.WAITING else com.rk.hardwaretest.audio.MicrophoneHealth.NO_INPUT;if(monitoring&&selected!=null&&ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)withContext(Dispatchers.IO){val rate=16000;val size=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(size>0){val r=AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2).apply{preferredDevice=selected;startRecording()};val b=ShortArray(size);try{while(kotlinx.coroutines.currentCoroutineContext().isActive){val n=r.read(b,0,b.size,AudioRecord.READ_NON_BLOCKING);health=com.rk.hardwaretest.audio.microphoneHealth(n,peakDb);if(n>0){val level=com.rk.hardwaretest.audio.pcmLevel(b.copyOf(n));peakDb=level.peakDbfs;rmsDb=level.rmsDbfs;health=com.rk.hardwaretest.audio.microphoneHealth(n,peakDb);routedName=r.routedDevice?.productName?.toString()?:"系统默认路由"}else delay(50)}}finally{r.stop();r.release()}}}};val band=com.rk.hardwaretest.audio.levelBand(peakDb);val barColor=when(band){com.rk.hardwaretest.audio.LevelBand.CLIPPING->Color.Red;com.rk.hardwaretest.audio.LevelBand.LOUD->Color(0xFFFF9800);com.rk.hardwaretest.audio.LevelBand.NORMAL->Color(0xFF4CAF50);com.rk.hardwaretest.audio.LevelBand.QUIET->Color(0xFFFFC107);else->Color.Gray};Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("麦克风实时检测",style=MaterialTheme.typography.headlineSmall);Row(verticalAlignment=Alignment.CenterVertically){Text("实时监测常开",modifier=Modifier.weight(1f));Switch(checked=monitoring,onCheckedChange={monitoring=it})};Text("请求：${selected?.productName?:"未发现输入设备"} · ID ${selected?.id?:"-"}");Text("实际使用：$routedName");Row{Button(onClick={if(devices.isNotEmpty())index=(index-1+devices.size)%devices.size}){Text("‹")};Text(" ${index+1}/${devices.size} ",modifier=Modifier.padding(12.dp));Button(onClick={if(devices.isNotEmpty())index=(index+1)%devices.size}){Text("›")}};Text("实时峰值：${"%.1f".format(peakDb)} dBFS · RMS：${"%.1f".format(rmsDb)} dBFS");LinearProgressIndicator(progress={((peakDb+60f)/60f).coerceIn(0f,1f)},color=barColor,modifier=Modifier.fillMaxWidth());Text(if(health==com.rk.hardwaretest.audio.MicrophoneHealth.NO_INPUT)"无音频输入：麦克风可能损坏、未连接或无权限" else band.label,color=barColor);ResultCard("最近一次测试",result);Button(onClick={retry(selected)}){Text("重新测试")}}}
@Composable private fun CameraPage(context: Context, result: TestResult?, update: (Pair<String, TestResult>) -> Unit) {
    val ids = remember { context.getSystemService(CameraManager::class.java).cameraIdList.toList() }
    var index by remember { mutableIntStateOf(0) }; var exposure by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf("连续自动") }; var resolution by remember { mutableStateOf("自动") }; var open by remember { mutableStateOf<String?>(null) }
    var controller by remember { mutableStateOf<Camera2PreviewController?>(null) }
    var phase by remember { mutableStateOf(CameraQualityPhase.IDLE) }
    var remaining by remember { mutableIntStateOf(8) }
    var qualityStatus by remember { mutableStateOf("尚未执行录像质量检测") }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val currentId = ids.getOrElse(index) { "0" }
    val extractor: AiFrameFeatureExtractor = remember { runCatching { RknnFeatureExtractor(context) }.getOrElse { UnavailableFeatureExtractor("RKNN Runtime 不可用：${it.message ?: it.javaClass.simpleName}") } }
    var profile by remember(currentId) { mutableStateOf(loadCameraSceneProfile(context, currentId)) }
    var baseline by remember(currentId) { mutableStateOf(loadStoredBaseline(context, currentId)) }
    var editingRoi by remember(currentId) { mutableStateOf(false) }
    var draftRoi by remember(currentId) { mutableStateOf(profile.roi) }
    var previewMetrics by remember(currentId) { mutableStateOf<CameraPreviewMetrics?>(null) }
    val baselineMatches = baseline?.let { baselineMatchesRoi(it, profile.roi) } == true
    val actions = sceneActionState(profile, baselineMatches)
    var aiStatus by remember(currentId) { mutableStateOf(
        when {
            profile.mode == CameraSceneMode.VARIABLE -> "变化场景模型尚未配置"
            baseline == null -> "摄像头 $currentId：尚未建立基线"
            !baselineMatches -> "ROI 已更新，需重建基线"
            else -> "摄像头 $currentId：已建立基线（${baseline!!.baseline.groups.values.sumOf { it.sampleCount }} 帧）"
        }
    ) }
    var buildingBaseline by remember { mutableStateOf(false) }
    var baselinePhase by remember { mutableStateOf(BaselineCapturePhase.IDLE) }
    var baselineRemaining by remember { mutableIntStateOf(8) }
    var baselineRecordingJob by remember { mutableStateOf<Job?>(null) }
    val baselinePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            buildingBaseline = true
            aiStatus = "正在建立基线…"
            when (val created = withContext(Dispatchers.Default) { createBaselineFromVideo(context, uri, profile, extractor) }) {
                is BaselineCreationResult.Created -> {
                    baseline = StoredSceneBaseline(created.baseline, profile.roi)
                    saveStoredBaseline(context, currentId, baseline!!)
                    aiStatus = "摄像头 $currentId：已建立基线（${created.acceptedFrames} 帧）"
                }
                is BaselineCreationResult.Failed -> aiStatus = created.reason
            }
            buildingBaseline = false
        }
    }
    val busy = phase != CameraQualityPhase.IDLE || baselinePhase != BaselineCapturePhase.IDLE || buildingBaseline
    val exposureRange: Range<Int> = remember(currentId) { context.getSystemService(CameraManager::class.java).getCameraCharacteristics(currentId).get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0) }
    val resolutionChoices = remember(currentId) { context.getSystemService(CameraManager::class.java).getCameraCharacteristics(currentId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.map { "${it.width}×${it.height}" }?.distinct() ?: emptyList() }
    Row(
        Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(Color.Black).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1.65f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraPreview(
                id = currentId,
                zoom = 1f,
                exposure = exposure,
                resolution = resolution,
                focus = focus,
                roi = if (editingRoi) draftRoi else profile.roi,
                editingRoi = editingRoi,
                onRoiDrag = { draftRoi = it },
                onController = { controller = it },
                onMetrics = { previewMetrics = it },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !busy && actions.canCreateBaseline, onClick = { baselinePicker.launch(arrayOf("video/*")) }) { Text(if (buildingBaseline) "正在建立基线…" else "导入录像建立基线") }
                Button(modifier = Modifier.weight(1f), enabled = !busy && controller != null && actions.canCreateBaseline, onClick = {
                val output = File(context.getExternalFilesDir("evidence"), "camera-baseline-${System.currentTimeMillis()}.mp4")
                baselinePhase = BaselineCapturePhase.PREPARING
                aiStatus = "正在准备录制正常基线…"
                controller?.startRecording(output, onStarted = {
                    scope.launch {
                        baselinePhase = BaselineCapturePhase.RECORDING
                        baselineRemaining = 8
                        baselineRecordingJob?.cancel()
                        baselineRecordingJob = launch {
                            repeat(8) { step -> baselineRemaining = 8 - step; delay(1_000) }
                            baselinePhase = BaselineCapturePhase.ANALYSING
                            aiStatus = "正在从当前摄像头录像建立基线…"
                            controller?.stopRecording(onStopped = { file ->
                                scope.launch {
                                    when (val created = withContext(Dispatchers.Default) { createBaselineFromVideo(context, android.net.Uri.fromFile(file), profile, extractor) }) {
                                        is BaselineCreationResult.Created -> {
                                            baseline = StoredSceneBaseline(created.baseline, profile.roi)
                                            saveStoredBaseline(context, currentId, baseline!!)
                                            aiStatus = "摄像头 $currentId：已建立基线（${created.acceptedFrames} 帧；录像：${file.name}）"
                                        }
                                        is BaselineCreationResult.Failed -> aiStatus = created.reason
                                    }
                                    baselinePhase = BaselineCapturePhase.IDLE
                                }
                            }, onError = { message -> scope.launch { aiStatus = message; baselinePhase = BaselineCapturePhase.IDLE } })
                        }
                    }
                }, onError = { message -> scope.launch { aiStatus = message; baselinePhase = BaselineCapturePhase.IDLE } })
                }) { Text(baselineCaptureLabel(baselinePhase, baselineRemaining)) }
                Button(modifier = Modifier.weight(1f), enabled = canStartCameraDetection(busy, controller != null, actions), onClick = {
                val output = File(context.getExternalFilesDir("evidence"), "camera-quality-${System.currentTimeMillis()}.mp4")
                phase = CameraQualityPhase.PREPARING
                qualityStatus = "正在准备录像…"
                controller?.startRecording(output, onStarted = {
                    scope.launch {
                        phase = CameraQualityPhase.RECORDING
                        remaining = 8
                        qualityStatus = "正在录制质量检测视频…"
                        recordingJob?.cancel()
                        recordingJob = launch {
                            repeat(8) { step -> remaining = 8 - step; delay(1_000) }
                            phase = CameraQualityPhase.ANALYSING
                            qualityStatus = "正在分析录制视频…"
                            controller?.stopRecording(onStopped = { file ->
                                scope.launch {
                                    val analysis = withContext(Dispatchers.Default) { analyseRecordedVideo(file, profile, baseline, extractor) }
                                    val assessment = analysis.assessment
                                    val combinedReasons = combineQualityReasons(assessment.reasons, analysis.aiReasons)
                                    val status = when {
                                        assessment.status == com.rk.hardwaretest.camera.RecordedQualityStatus.FAIL -> TestStatus.FAIL
                                        combinedReasons.isNotEmpty() -> TestStatus.WARNING
                                        else -> when (assessment.status) {
                                        com.rk.hardwaretest.camera.RecordedQualityStatus.PASS -> TestStatus.PASS
                                        com.rk.hardwaretest.camera.RecordedQualityStatus.WARNING -> TestStatus.WARNING
                                        com.rk.hardwaretest.camera.RecordedQualityStatus.FAIL -> TestStatus.FAIL
                                        }
                                    }
                                    qualityStatus = if (combinedReasons.isEmpty()) "画面质量正常" else combinedReasons.joinToString("；")
                                    update("camera" to TestResult(status, "录像质量检测：$qualityStatus", mapOf(
                                        "已分析帧数" to analysis.frameCount.toString(),
                                        "最差清晰度" to "%.1f".format(assessment.worstSharpness),
                                        "最差暗部比例" to "%.1f%%".format(assessment.worstDarkPercent),
                                        "最差亮部比例" to "%.1f%%".format(assessment.worstBrightPercent),
                                        "最差对比度" to "%.1f".format(assessment.worstContrast),
                                        "AI 状态" to analysis.aiStatus,
                                        "AI 异常分" to (analysis.aiScore?.let { "%.3f".format(it) } ?: "-"),
                                        "AI 亮度组" to analysis.aiBands,
                                        "AI 原因" to analysis.aiReasons.joinToString("；").ifEmpty { "无" },
                                        "相对过曝亮度组" to (analysis.relativeOverexposureDiagnostic?.band?.name ?: "-"),
                                        "相对过曝基线组" to (analysis.relativeOverexposureDiagnostic?.baselineBand?.name ?: "-"),
                                        "基线亮部比例" to (analysis.relativeOverexposureDiagnostic?.baselineBrightPercent?.let { "%.1f%%".format(it) } ?: "-"),
                                        "相对过曝阈值" to (analysis.relativeOverexposureDiagnostic?.threshold?.let { "%.1f%%".format(it) } ?: "-"),
                                        "当前相对亮部比例" to (analysis.relativeOverexposureDiagnostic?.currentBrightPercent?.let { "%.1f%%".format(it) } ?: "-"),
                                        "局部强光区域" to (analysis.localHotspotPercent?.let { "%.1f%%".format(it) } ?: "-"),
                                        "录像证据" to file.name,
                                    ), listOf(file.name)))
                                    phase = CameraQualityPhase.IDLE
                                }
                            }, onError = { message -> scope.launch { qualityStatus = message; phase = CameraQualityPhase.IDLE } })
                        }
                    }
                }, onError = { message -> scope.launch { qualityStatus = message; phase = CameraQualityPhase.IDLE } })
                }) { Text(when (phase) { CameraQualityPhase.PREPARING -> "正在准备录像…"; CameraQualityPhase.RECORDING -> "录制中：$remaining 秒"; CameraQualityPhase.ANALYSING -> "正在分析视频…"; CameraQualityPhase.IDLE -> "录制并检测" }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(modifier = Modifier.weight(1f), selected = profile.mode == CameraSceneMode.FIXED, onClick = {
                    profile = profile.copy(mode = CameraSceneMode.FIXED)
                    saveCameraSceneProfile(context, currentId, profile)
                    aiStatus = if (baseline?.let { baselineMatchesRoi(it, profile.roi) } == true) "固定场景模型就绪" else "ROI 已更新，需重建基线"
                }, label = { Text("固定场景") }, enabled = !busy)
                FilterChip(modifier = Modifier.weight(1f), selected = profile.mode == CameraSceneMode.VARIABLE, onClick = {
                    profile = profile.copy(mode = CameraSceneMode.VARIABLE)
                    saveCameraSceneProfile(context, currentId, profile)
                    aiStatus = "变化场景模型尚未配置"
                }, label = { Text("变化场景") }, enabled = !busy)
                Button(modifier = Modifier.weight(1f), enabled = !busy, onClick = { draftRoi = profile.roi; editingRoi = true }) { Text("设置 ROI") }
            }
        }
        VerticalDivider(color = Color(0xFF374151))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("相机参数与实时状态", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, modifier = Modifier.clickable(enabled = !busy) { index = (index - 1 + ids.size) % ids.size }.padding(8.dp))
                Text("摄像头 $currentId · ${index + 1}/${ids.size}", color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("›", color = Color.White, modifier = Modifier.clickable(enabled = !busy) { index = (index + 1) % ids.size }.padding(8.dp))
            }
            listOf("曝光" to "$exposure", "对焦" to focus, "分辨率" to resolution).forEach { (label,value) ->
                Text("$label：$value ${if(open==label) "⌃" else "⌄"}", color=Color.White, modifier=Modifier.fillMaxWidth().background(Color(0xAA111827),MaterialTheme.shapes.small).clickable(enabled = !busy) { open=if(open==label)null else label }.padding(10.dp))
                if(open==label) Column(Modifier.background(Color(0xDD111827),MaterialTheme.shapes.small)) {
                    if(label=="曝光") Slider(value=exposure.toFloat(),onValueChange={exposure=it.roundToInt()},valueRange=exposureRange.lower.toFloat()..exposureRange.upper.toFloat(),enabled=!busy,modifier=Modifier.fillMaxWidth())
                    if(label=="曝光"&&exposureRange.lower==exposureRange.upper)Text("该摄像头不支持曝光补偿（范围 0）",color=Color.Yellow,modifier=Modifier.padding(10.dp))
                    val choices=when(label){"曝光"->listOf(exposureRange.lower,0,exposureRange.upper).filter{it>=exposureRange.lower&&it<=exposureRange.upper}.distinct().map{it.toString()};"对焦"->listOf("连续自动","自动");else->listOf("自动")+resolutionChoices}
                    choices.forEach { option -> Text(option,color=Color.White,modifier=Modifier.clickable(enabled = !busy) { when(label){"曝光"->exposure=option.toInt();"对焦"->focus=option;else->resolution=option};open=null }.padding(10.dp)) }
                }
            }
            previewMetrics?.summaryLines()?.forEach { line ->
                Text(line, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(Color(0xAA111827), MaterialTheme.shapes.small).padding(6.dp))
            }
            Text("场景状态", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(aiStatus, color = Color.White, style = MaterialTheme.typography.bodySmall)
            Text("录像状态", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(qualityStatus, color = Color.White, style = MaterialTheme.typography.bodySmall)
            if (editingRoi) {
                RoiEditor(draftRoi, onChange = { draftRoi = it }, onConfirm = {
                    if (draftRoi.isValid()) {
                        profile = profile.copy(roi = draftRoi)
                        saveCameraSceneProfile(context, currentId, profile)
                        editingRoi = false
                        aiStatus = if (baseline?.let { baselineMatchesRoi(it, profile.roi) } == true) "固定场景模型就绪" else "ROI 已更新，需重建基线"
                    }
                }, onCancel = { editingRoi = false })
            }
        }
    }
}
@Composable private fun CameraPreview(
    id: String,
    zoom: Float,
    exposure: Int,
    resolution: String,
    focus: String,
    roi: NormalizedRoi,
    editingRoi: Boolean,
    onRoiDrag: (NormalizedRoi) -> Unit,
    onController: (Camera2PreviewController?) -> Unit,
    onMetrics: (CameraPreviewMetrics) -> Unit,
) {
    var controller by remember { mutableStateOf<Camera2PreviewController?>(null) }
    val metricsCallback = remember { LatestMetricsCallback(onMetrics) }
    metricsCallback.update(onMetrics)
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(1.dp, Color(0xFF374151))) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { view ->
                    controller = Camera2PreviewController(ctx, view, onState = {}, onMetrics = metricsCallback::publish)
                    onController(controller)
                }
            },
            update = {
                val size = if (resolution == "自动") null else resolution.split("×").let { Size(it[0].toInt(), it[1].toInt()) }
                controller?.setConfig(CameraRequestConfig(id, exposure, zoom, if (focus == "自动") CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, null, size))
            },
            modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset(maxWidth * roi.left, maxHeight * roi.top)
                    .size(maxWidth * (roi.right - roi.left), maxHeight * (roi.bottom - roi.top))
                    .border(2.dp, if (editingRoi) Color.Yellow else Color.Cyan),
            )
            if (editingRoi) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        var startX = 0f
                        var startY = 0f
                        detectDragGestures(
                            onDragStart = { start ->
                                startX = start.x / size.width
                                startY = start.y / size.height
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                onRoiDrag(normalizedRoiFromDrag(startX, startY, change.position.x / size.width, change.position.y / size.height))
                            },
                        )
                    },
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            onController(null)
            controller?.release()
        }
    }
}
@Composable private fun RoiEditor(roi: NormalizedRoi, onChange: (NormalizedRoi) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.background(Color(0xDD111827), MaterialTheme.shapes.medium).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ROI 编辑（可分别调整四条边）", color = Color.White)
        listOf("左" to roi.left, "上" to roi.top, "右" to roi.right, "下" to roi.bottom).forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label ${"%.2f".format(value)}", color = Color.White, modifier = Modifier.width(72.dp))
                Slider(value = value, onValueChange = { changed ->
                    onChange(when (label) {
                        "左" -> roi.copy(left = changed.coerceAtMost(roi.right - .01f))
                        "上" -> roi.copy(top = changed.coerceAtMost(roi.bottom - .01f))
                        "右" -> roi.copy(right = changed.coerceAtLeast(roi.left + .01f))
                        else -> roi.copy(bottom = changed.coerceAtLeast(roi.top + .01f))
                    })
                }, valueRange = 0f..1f, modifier = Modifier.width(180.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, enabled = roi.isValid()) { Text("确认") }
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = { onChange(NormalizedRoi.fullFrame()) }) { Text("重置全画面") }
        }
    }
}
@Composable private fun ResultCard(name:String,r:TestResult?){ Card { Column(Modifier.padding(12.dp)){ Text(name+"："+(r?.status?.name?:"未测试")); Text(r?.summary?:""); r?.details?.forEach{Text("${it.key}: ${it.value}")} } } }
private fun analyseRecordedVideo(file: File, profile: CameraSceneProfile, storedBaseline: StoredSceneBaseline?, extractor: AiFrameFeatureExtractor): RecordedVideoAnalysis {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtMost(8_000L) ?: 8_000L
        val frames = recordingAnalysisTimesUs(durationMs).mapNotNull { timeUs ->
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { bitmap ->
                try {
                    val scale = min(1f, 320f / max(bitmap.width, bitmap.height).toFloat())
                    val width = max(1, (bitmap.width * scale).roundToInt())
                    val height = max(1, (bitmap.height * scale).roundToInt())
                    val reduced = if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
                    try {
                        val pixels = IntArray(width * height)
                        reduced.getPixels(pixels, 0, width, 0, 0, width, height)
                        val cropped = if (profile.mode == CameraSceneMode.FIXED) profile.roi.cropArgb(pixels, width, height) else com.rk.hardwaretest.camera.CroppedArgbFrame(pixels, width, height)
                        Triple(bitmapFrameSample(cropped.argb, cropped.width, cropped.height), bitmapAiInput(cropped.argb, cropped.width, cropped.height), spatialSignature(cropped.argb, cropped.width, cropped.height))
                    } finally {
                        if (reduced !== bitmap) reduced.recycle()
                    }
                } finally { bitmap.recycle() }
            }
        }
        val initialAssessment = assessRecordedFrames(frames.map { it.first })
        if (profile.mode == CameraSceneMode.VARIABLE) return RecordedVideoAnalysis(initialAssessment, frames.size, aiStatus = "变化场景模型尚未配置")
        val baseline = storedBaseline?.takeIf { baselineMatchesRoi(it, profile.roi) }?.baseline
        if (baseline == null) return RecordedVideoAnalysis(initialAssessment, frames.size, aiStatus = if (storedBaseline == null) "尚未建立固定场景基线" else "ROI 已更新，需重建基线")
        val relativeExposureFrames = frames.map { (sample, input, _) ->
            AiFrameFeature(
                input.luma, input.redMean, input.greenMean, input.blueMean, FloatArray(0),
                brightPercent = sample.brightPercent,
            )
        }
        val overexposureDiagnostic = relativeOverexposureDiagnostic(baseline, relativeExposureFrames)
        val spatialFrames = frames.map { it.third }
        val hotspot = assessSpatialBrightHotspot(baseline, spatialFrames)
        val overexposureReason = if (hotspot.hasBrightHotspot) "画面过曝（局部强光）" else relativeOverexposureReason(baseline, relativeExposureFrames)
        val assessment = assessRecordedFrames(frames.map { it.first }, overexposureReason)
        val aiFrames = mutableListOf<AiFrameFeature>()
        var unavailable: String? = null
        frames.forEach { (sample, input, spatial) ->
            if (unavailable == null) when (val result = extractor.extract(input.rgb224)) {
                is AiFeatureResult.Available -> aiFrames += AiFrameFeature(
                    input.luma, input.redMean, input.greenMean, input.blueMean, result.embedding,
                    spatial = spatial, brightPercent = sample.brightPercent,
                )
                is AiFeatureResult.Unavailable -> unavailable = result.reason
            }
        }
        if (unavailable != null) return RecordedVideoAnalysis(assessment, frames.size, aiStatus = "AI 不可用：$unavailable", relativeOverexposureDiagnostic = overexposureDiagnostic, localHotspotPercent = hotspot.highestBrightenedPercent)
        if (aiFrames.isEmpty()) return RecordedVideoAnalysis(assessment, frames.size, aiStatus = "AI 未取得有效特征", relativeOverexposureDiagnostic = overexposureDiagnostic, localHotspotPercent = hotspot.highestBrightenedPercent)
        val ai = assessSceneAnomalies(baseline, aiFrames)
        val spatial = assessSpatialOcclusion(baseline, spatialFrames)
        val colors = aiFrames.mapNotNull { frame ->
            val group = baseline.groups[lightBand(frame.luma)] ?: baseline.groups.values.minByOrNull { kotlin.math.abs(it.redMean - frame.redMean) }
            group?.let { colorCastReason(it, frame) }
        }.distinct()
        val reasons = buildList {
            if (spatial.hasOcclusion && !hotspot.hasBrightHotspot) add("画面区域被遮挡或场景严重变化")
            addAll(colors)
            if (ai.hasAnomaly && colors.isEmpty() && !spatial.hasOcclusion) add("画面异常，疑似镜头污渍、水雾或场景偏移")
        }
        val spatialStatus = if (baseline.groups.values.any { it.spatial != null }) "空间遮挡已检测" else "当前基线缺少空间签名，请重新建立该摄像头基线"
        val brightStatus = if (hasRelativeOverexposureStatistics(baseline)) "相对过曝已检测" else "需重新建立基线以启用相对过曝判定"
        RecordedVideoAnalysis(assessment, frames.size, reasons, "AI 已分析 ${aiFrames.size} 帧；$spatialStatus；$brightStatus", ai.highestScore, aiFrames.map { lightBand(it.luma).name }.distinct().joinToString(","), overexposureDiagnostic, hotspot.highestBrightenedPercent)
    } catch (_: Exception) {
        RecordedVideoAnalysis(assessRecordedFrames(emptyList()), 0, aiStatus = "视频解码失败，已跳过 AI 检测")
    } finally { retriever.release() }
}

private fun createBaselineFromVideo(context: Context, uri: android.net.Uri, profile: CameraSceneProfile, extractor: AiFrameFeatureExtractor): BaselineCreationResult {
    val retriever = MediaMetadataRetriever()
    return try {
        if (!shouldRunFixedSceneModel(profile)) return BaselineCreationResult.Failed("变化场景模型尚未配置")
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
        val intervalMs = maxOf(34L, durationMs / 240L)
        val features = videoSampleTimesUs(durationMs, intervalMs).take(240).mapNotNull { timeUs ->
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { bitmap ->
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val cropped = profile.roi.cropArgb(pixels, bitmap.width, bitmap.height)
                    val sample = bitmapFrameSample(cropped.argb, cropped.width, cropped.height)
                    if (!isUsableBaselineFrame(sample)) null else {
                        val input = bitmapAiInput(cropped.argb, cropped.width, cropped.height)
                        when (val extracted = extractor.extract(input.rgb224)) {
                            is AiFeatureResult.Available -> AiFrameFeature(
                                input.luma, input.redMean, input.greenMean, input.blueMean, extracted.embedding,
                                spatial = spatialSignature(cropped.argb, cropped.width, cropped.height),
                                brightPercent = sample.brightPercent,
                            )
                            is AiFeatureResult.Unavailable -> throw IllegalStateException("AI 不可用：${extracted.reason}")
                        }
                    }
                } finally { bitmap.recycle() }
            }
        }
        val baseline = buildSceneBaseline(features) ?: return BaselineCreationResult.Failed("有效正常帧不足，未覆盖原有基线")
        BaselineCreationResult.Created(baseline, features.size)
    } catch (error: Exception) {
        BaselineCreationResult.Failed("建立基线失败：${error.message ?: error.javaClass.simpleName}")
    } finally { retriever.release() }
}
private fun cameraTest(c:Context):Pair<String,TestResult>{
 if(ContextCompat.checkSelfPermission(c,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return "camera" to TestResult(TestStatus.PERMISSION_REQUIRED,"未授予摄像头权限")
 val m=c.getSystemService(CameraManager::class.java); val d=m.cameraIdList.associateWith { id -> val x=m.getCameraCharacteristics(id); val raw=x.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING); val face=when(raw){0->"前置";1->"后置";2->"外接";else->"未知"}; "方向：$face（原始值：$raw）；支持 ${x.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.size?:0} 种预览尺寸" }
 return "camera" to TestResult(if(d.isEmpty())TestStatus.FAIL else TestStatus.PASS,"已识别 ${d.size} 路摄像头；上方显示后置实时预览",d)
}
private fun speakerTest(c:Context,hz:Int=440,seconds:Float=2f,amplitude:Float=.25f):Pair<String,TestResult>{ val a=c.getSystemService(AudioManager::class.java); val sr=48000; val n=(sr*seconds).roundToInt(); val b=ShortArray(n){(sin(2*Math.PI*hz*it/sr)*amplitude.coerceIn(.05f,1f)*Short.MAX_VALUE).toInt().toShort()}; val t=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(b.size*2).setTransferMode(AudioTrack.MODE_STATIC).build(); t.write(b,0,b.size); t.play(); val p=t.playbackHeadPosition; Thread.sleep(min(300L,(seconds*1000).toLong())); val advanced=t.playbackHeadPosition-p; val routed=t.routedDevice; val details=mapOf("播放头推进帧数" to advanced.toString(),"实际输出设备" to (routed?.productName?.toString()?:"系统默认"),"输出设备类型" to audioDeviceTypeName(routed?.type),"采样率" to "${t.sampleRate} Hz","声道数" to t.channelCount.toString(),"缓冲区" to "${t.bufferSizeInFrames} 帧","测试音" to "$hz Hz / ${"%.1f".format(seconds)} 秒 / ${(amplitude*100).roundToInt()}% PCM"); Handler(Looper.getMainLooper()).postDelayed({t.stop();t.release()},(seconds*1000).toLong()); return "speaker" to TestResult(if(advanced>0&&a.getStreamVolume(AudioManager.STREAM_MUSIC)>0)TestStatus.PASS else TestStatus.WARNING,"媒体音量 ${a.getStreamVolume(AudioManager.STREAM_MUSIC)}/${a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",details) }
private fun audioDeviceTypeName(type:Int?):String=when(type){AudioDeviceInfo.TYPE_BUILTIN_SPEAKER->"内置扬声器";AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET->"有线耳机";AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLUETOOTH_SCO->"蓝牙音频";AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_HEADSET->"USB 音频";null->"系统默认";else->"其他输出设备"}
private fun microphoneTest(c:Context,selected:AudioDeviceInfo?):Pair<String,TestResult>{ if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return "microphone" to TestResult(TestStatus.PERMISSION_REQUIRED,"未授予录音权限"); val a=c.getSystemService(AudioManager::class.java); val dev=a.getDevices(AudioManager.GET_DEVICES_INPUTS); val rate=16000; val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT); val r=AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,min*2).apply{preferredDevice=selected}; val s=ShortArray(rate*3); r.startRecording(); val routed=r.routedDevice; var pos=0; val deadline=System.currentTimeMillis()+3500; while(pos<s.size&&System.currentTimeMillis()<deadline){val read=r.read(s,pos,s.size-pos,AudioRecord.READ_NON_BLOCKING);if(read>0)pos+=read else Thread.sleep(20)}; r.stop();r.release(); val samples=s.copyOf(pos); val peak=samples.maxOfOrNull{abs(it.toInt())}?:0; val rms=if(samples.isEmpty())0.0 else sqrt(samples.map{it.toDouble()*it}.average()); val file=File(c.getExternalFilesDir("evidence"),"mic-${System.currentTimeMillis()}.pcm").also{it.parentFile?.mkdirs();FileOutputStream(it).use{x->samples.forEach{v->x.write(v.toInt());x.write(v.toInt() shr 8)}}}; return "microphone" to TestResult(if(peak>500)TestStatus.PASS else TestStatus.WARNING,if(pos==0)"未收到音频帧：设备可能损坏或未连接" else "输入设备 ${dev.size} 个；峰值 ${"%.1f".format(20*log10(max(1,peak).toDouble()/32767))} dBFS",mapOf("采集帧数" to pos.toString(),"RMS dBFS" to "%.1f".format(20*log10(max(1.0,rms)/32767)),"请求麦克风" to (selected?.productName?.toString()?:"系统默认"),"实际麦克风" to (routed?.productName?.toString()?:"未知")),listOf(file.name)) }
private fun networkTest(c:Context):Pair<String,TestResult>{
    val cm=c.getSystemService(ConnectivityManager::class.java); val n=cm.activeNetwork; val cap=n?.let{cm.getNetworkCapabilities(it)}; val lp=n?.let{cm.getLinkProperties(it)}
    val tr=listOf("以太网" to NetworkCapabilities.TRANSPORT_ETHERNET,"Wi-Fi" to NetworkCapabilities.TRANSPORT_WIFI,"蜂窝" to NetworkCapabilities.TRANSPORT_CELLULAR).firstOrNull{cap?.hasTransport(it.second)==true}?.first?:"无"
    val good=cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true; val interfaceName=lp?.interfaceName.orEmpty(); val interfaceLabel=interfaceName.ifEmpty{"无"}; val nicName=runCatching{NetworkInterface.getByName(interfaceName)?.displayName}.getOrNull()?:"未知"
    val wifi=if(tr=="Wi-Fi")c.applicationContext.getSystemService(WifiManager::class.java).connectionInfo else null; val ssid=wifi?.ssid?.takeIf{it!="<unknown ssid>"}?.trim('"')?:"不可读取（系统权限限制或非 Wi‑Fi）"
    val generic=wifi?.linkSpeed?:-1
    val tx=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)wifi?.txLinkSpeedMbps?:-1 else -1
    val rx=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)wifi?.rxLinkSpeedMbps?:-1 else -1
    val maxTx=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)wifi?.maxSupportedTxLinkSpeedMbps?:-1 else -1
    val maxRx=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)wifi?.maxSupportedRxLinkSpeedMbps?:-1 else -1
    val totalRx=TrafficStats.getTotalRxBytes().takeIf{it>=0}?.let{"$it 字节"}?:"系统未提供"; val totalTx=TrafficStats.getTotalTxBytes().takeIf{it>=0}?.let{"$it 字节"}?:"系统未提供"
    return "network" to TestResult(if(good)TestStatus.PASS else TestStatus.WARNING,"$tr；互联网验证：$good",mapOf("网络接口" to "$nicName ($interfaceLabel)","Wi‑Fi 名称" to ssid,"通用链路速率" to com.rk.hardwaretest.network.wifiLinkSpeedLabel(generic),"发送链路速率" to com.rk.hardwaretest.network.wifiLinkSpeedLabel(tx),"接收链路速率" to com.rk.hardwaretest.network.wifiLinkSpeedLabel(rx),"最大支持发送" to com.rk.hardwaretest.network.wifiLinkSpeedLabel(maxTx),"最大支持接收" to com.rk.hardwaretest.network.wifiLinkSpeedLabel(maxRx),"网络估算上行" to "${cap?.linkUpstreamBandwidthKbps?:0} Kbps","网络估算下行" to "${cap?.linkDownstreamBandwidthKbps?:0} Kbps","Wi‑Fi 频率" to (wifi?.frequency?.let{"$it MHz"}?:"未提供"),"Wi‑Fi 信号" to (wifi?.rssi?.let{"$it dBm"}?:"未提供"),"驱动版本" to "Android 标准 API 不提供","累计接收" to totalRx,"累计传输" to totalTx,"IP" to (lp?.linkAddresses?.joinToString()?:"无"),"DNS" to (lp?.dnsServers?.joinToString()?:"无"),"网关" to (lp?.routes?.firstOrNull{it.isDefaultRoute}?.gateway?.hostAddress?:"无")))
}
private fun publicDownloadSpeedTest():String=try{val connection=(URL("https://speed.cloudflare.com/__down?bytes=10000000").openConnection() as HttpURLConnection).apply{connectTimeout=12_000;readTimeout=20_000;setRequestProperty("User-Agent","RK-Hardware-Test/1.0")};val start=System.nanoTime();var total=0L;connection.inputStream.use{input->val buffer=ByteArray(32*1024);while(total<10_000_000L){val read=input.read(buffer);if(read<0)break;total+=read}};val seconds=(System.nanoTime()-start)/1_000_000_000.0;"下载 ${"%.2f".format(total/1_000_000.0)} MB，用时 ${"%.2f".format(seconds)} 秒，${com.rk.hardwaretest.network.formatBitsPerSecond((total*8/seconds).toLong())}"}catch(e:Exception){"测速失败：${e.message?:e.javaClass.simpleName}"}
private fun saveReport(c:Context,r:Map<String,TestResult>){val f=File(c.getExternalFilesDir("reports"),"report-${System.currentTimeMillis()}.json");f.parentFile?.mkdirs();f.writeText("{\"device\":\"${Build.MODEL}\",\"results\":{${r.entries.joinToString(","){"\"${it.key}\":\"${it.value.status}\""}}}}")}
