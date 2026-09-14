package ir.daftarclass.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Toast

@Entity(tableName = "students")
data class Student(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val guardianPhone: String,
    val internalId: String
)

@Entity(
    tableName = "attendance",
    primaryKeys = ["date", "studentId", "mode"]
)
data class Attendance(
    val date: String,
    val studentId: Long,
    val mode: String,
    val present: Boolean,
    val homeworkComplete: Boolean? = null,
    val smsSent: Boolean = false
)

@Entity(tableName = "settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val school: String = "",
    val teacher: String = "",
    val className: String = "",
    val smsTemplate: String =
        "ولی محترم، به اطلاع می‌رساند فرزند شما [نام] امروز [تاریخ] در مدرسه [مدرسه] حضور نداشته است."
)

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY name")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Student>>
    @Insert suspend fun insert(s: Student)
    @Update suspend fun update(s: Student)
    @Delete suspend fun delete(s: Student)
    @Query("SELECT * FROM students") suspend fun all(): List<Student>
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance WHERE date=:date AND mode=:mode")
    fun observe(date: String, mode: String): kotlinx.coroutines.flow.Flow<List<Attendance>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(a: Attendance)
    @Query("SELECT * FROM attendance") suspend fun all(): List<Attendance>
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id=1")
    fun observe(): kotlinx.coroutines.flow.Flow<AppSettings?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(s: AppSettings)
}

@Database(entities = [Student::class, Attendance::class, AppSettings::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun students(): StudentDao
    abstract fun attendance(): AttendanceDao
    abstract fun settings(): SettingsDao
    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        fun get(c: Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "daftar_class.db")
                .build().also { INSTANCE = it }
        }
    }
}

class MainVm(private val db: AppDb) : ViewModel() {
    val students = db.students().observeAll().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = db.settings().observe().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), AppSettings())
    private val _screen = MutableStateFlow("home")
    val screen = _screen.asStateFlow()
    fun go(s: String) { _screen.value = s }
    fun addStudent(s: Student) = viewModelScope.launch { db.students().insert(s) }
    fun updateStudent(s: Student) = viewModelScope.launch { db.students().update(s) }
    fun deleteStudent(s: Student) = viewModelScope.launch { db.students().delete(s) }
    fun saveAttendance(a: Attendance) = viewModelScope.launch { db.attendance().upsert(a) }
    fun saveSettings(s: AppSettings) = viewModelScope.launch { db.settings().save(s) }
    fun records(date: String, mode: String) = db.attendance().observe(date, mode)
    fun exportJson(c: Context) = viewModelScope.launch {
        val students = db.students().all()
        val att = db.attendance().all()
        val settings = db.settings().observe().let { kotlinx.coroutines.flow.first(it) } ?: AppSettings()
        val json = buildString {
            append("{\"students\":[")
            students.forEachIndexed { i,s ->
                if(i>0) append(",")
                append("{\"id\":${s.id},\"name\":\"${esc(s.name)}\",\"guardianPhone\":\"${esc(s.guardianPhone)}\",\"internalId\":\"${esc(s.internalId)}\"}")
            }
            append("],\"attendance\":[")
            att.forEachIndexed { i,a ->
                if(i>0) append(",")
                append("{\"date\":\"${esc(a.date)}\",\"studentId\":${a.studentId},\"mode\":\"${a.mode}\",\"present\":${a.present},\"homeworkComplete\":${a.homeworkComplete},\"smsSent\":${a.smsSent}}")
            }
            append("],\"settings\":{\"school\":\"${esc(settings.school)}\",\"teacher\":\"${esc(settings.teacher)}\",\"className\":\"${esc(settings.className)}\",\"smsTemplate\":\"${esc(settings.smsTemplate)}\"}}")
        }
        val name = "دفترکلاس-${System.currentTimeMillis()}.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/دفتر کلاس")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let { c.contentResolver.openOutputStream(it)?.use { out -> out.write(json.toByteArray()) } }
    }
    private fun esc(x:String)=x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")
}


@Composable fun rememberVm(): MainVm {
    val c = LocalContext.current
    return viewModel(factory = object: androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T: ViewModel> create(modelClass: Class<T>): T =
            MainVm(AppDb.get(c)) as T
    })
}

private val Green = Color(0xFF3E8E63)
private val Pale = Color(0xFFF7FAF8)
private val Dark = Color(0xFF20312A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    CompositionLocalProvider(androidx.compose.ui.unit.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        val vm = rememberVm()
        val screen by vm.screen.collectAsState()
        MaterialTheme(colorScheme = lightColorScheme(primary=Green, background=Pale)) {
            Surface(Modifier.fillMaxSize(), color=Pale) {
                when(screen) {
                    "home" -> Home(vm)
                    "students" -> Students(vm)
                    "virtual" -> AttendanceScreen(vm, "virtual")
                    "present" -> AttendanceScreen(vm, "present")
                    "history" -> History(vm)
                    "settings" -> Settings(vm)
                }
            }
        }
    }
}

@Composable fun Header(title:String, vm:MainVm, back:Boolean=true) {
    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment=Alignment.CenterVertically) {
        if(back) IconButton({vm.go("home")}) { Icon(Icons.Default.ArrowForward, null) }
        Text(title, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold, color=Dark)
        Spacer(Modifier.weight(1f))
    }
}

@Composable fun Home(vm:MainVm) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(10.dp))
            Text("دفتر کلاس", style=MaterialTheme.typography.displaySmall, fontWeight=FontWeight.Bold, color=Dark)
            Text("مدیریت سریع کلاس و حضور و غیاب", color=Color.Gray)
            Spacer(Modifier.height(20.dp))
        }
        item { BigCard("💻  کلاس مجازی","برای حضور، تکالیف و گزارش عملکرد"){vm.go("virtual")} }
        item { BigCard("🏫  کلاس حضوری","برای حضور و غیاب و ارسال پیامک"){vm.go("present")} }
        item { SmallNav("👥","مدیریت دانش‌آموزان"){vm.go("students")} }
        item { SmallNav("🗂","سوابق"){vm.go("history")} }
        item { SmallNav("⚙️","تنظیمات"){vm.go("settings")} }
    }
}

@Composable fun BigCard(title:String, sub:String, click:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable{click()}, shape=RoundedCornerShape(24.dp), elevation=CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text(title, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text(sub, color=Color.Gray)
            Spacer(Modifier.height(18.dp))
            Button(click, modifier=Modifier.fillMaxWidth().height(52.dp)) { Text("ورود") }
        }
    }
}
@Composable fun SmallNav(icon:String,title:String,click:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable{click()}, shape=RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment=Alignment.CenterVertically) {
            Text(icon, style=MaterialTheme.typography.titleLarge); Spacer(Modifier.width(14.dp)); Text(title, fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronLeft,null)
        }
    }
}

@Composable fun Students(vm:MainVm) {
    val list by vm.students.collectAsState()
    var show by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Student?>(null) }
    Header("مدیریت دانش‌آموزان",vm)
    Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
        Button({editing=null;show=true}, Modifier.fillMaxWidth().height(52.dp)) { Text("افزودن دانش‌آموز") }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(list, key={it.id}) { s ->
                Card(shape=RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name,fontWeight=FontWeight.Bold)
                            Text("شناسه: ${s.internalId}  |  ولی: ${s.guardianPhone}",color=Color.Gray)
                        }
                        IconButton({editing=s;show=true}){Icon(Icons.Default.Edit,null)}
                        IconButton({vm.deleteStudent(s)}){Icon(Icons.Default.Delete,null)}
                    }
                }
            }
        }
    }
    if(show) StudentDialog(editing,{show=false}) { s ->
        if(editing==null) vm.addStudent(s) else vm.updateStudent(s)
        show=false
    }
}
@Composable fun StudentDialog(old:Student?,close:()->Unit,save:(Student)->Unit) {
    var name by remember{mutableStateOf(old?.name?:"")}
    var phone by remember{mutableStateOf(old?.guardianPhone?:"")}
    var iid by remember{mutableStateOf(old?.internalId?:"")}
    AlertDialog(onDismissRequest=close,confirmButton={Button({save(Student(old?.id?:0,name,phone,iid))}){Text("ذخیره")}},
        dismissButton={TextButton(close){Text("لغو")}},title={Text(if(old==null)"افزودن دانش‌آموز" else "ویرایش دانش‌آموز")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(name,{name=it},label={Text("نام و نام خانوادگی")})
            OutlinedTextField(phone,{phone=it},label={Text("شماره تلفن ولی")})
            OutlinedTextField(iid,{iid=it},label={Text("شناسه داخلی")})
        }})
}

fun shamsiDate():String {
    val d=Calendar.getInstance()
    val y=d.get(Calendar.YEAR)-621
    val m=d.get(Calendar.MONTH)+1
    val day=d.get(Calendar.DAY_OF_MONTH)
    val jm=when(m){1->"دی";2->"بهمن";3->"اسفند";4->"فروردین";5->"اردیبهشت";6->"خرداد";7->"تیر";8->"مرداد";9->"شهریور";10->"مهر";11->"آبان";else->"آذر"}
    return "$day $jm $y"
}

@Composable fun AttendanceScreen(vm:MainVm, mode:String) {
    val list by vm.students.collectAsState()
    val date=remember{shamsiDate()}
    val records by vm.records(date,mode).collectAsState(initial=emptyList())
    val map=records.associateBy{it.studentId}
    var finalized by remember{mutableStateOf(false)}
    val present=records.count{it.present}
    val absent=records.count{!it.present}
    val complete=records.count{it.homeworkComplete==true}
    val incomplete=records.count{it.homeworkComplete==false}
    Header(if(mode=="virtual")"کلاس مجازی" else "کلاس حضوری",vm)
    Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
        Text("امروز: $date",color=Color.Gray)
        Spacer(Modifier.height(10.dp))
        Stats(if(mode=="virtual") listOf("حاضر" to present,"غایب" to absent,"تکلیف کامل" to complete,"تکلیف ناقص" to incomplete)
        else listOf("حاضر" to present,"غایب" to absent))
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.weight(1f)) {
            items(list,key={it.id}) { s ->
                val a=map[s.id]
                StudentAttendanceRow(s,a,mode){att->vm.saveAttendance(att)}
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(mode=="virtual") Button({ Toast.makeText(LocalContext.current,"جلسه ذخیره شد",Toast.LENGTH_SHORT).show() },Modifier.weight(1f).height(52.dp)){Text("ثبت جلسه")}
            else Button({finalized=true},Modifier.weight(1f).height(52.dp)){Text("ثبت نهایی و پیامک")}
            if(mode=="virtual") OutlinedButton({ generateReport(LocalContext.current,list,records,date) },Modifier.weight(1f).height(52.dp)){Text("خروجی گزارش")}
        }
    }
    if(finalized) SmsConfirm(vm,list,records,date){finalized=false}
}

@Composable fun Stats(items:List<Pair<String,Int>>) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        items.forEach { (t,n)-> Card(Modifier.weight(1f),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("$n",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(t,style=MaterialTheme.typography.labelSmall)}}}
    }
}
@Composable fun StudentAttendanceRow(s:Student,a:Attendance?,mode:String,save:(Attendance)->Unit) {
    var present by remember(a?.present){mutableStateOf(a?.present)}
    var hw by remember(a?.homeworkComplete){mutableStateOf(a?.homeworkComplete)}
    Card(shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(s.name,fontWeight=FontWeight.Bold)
            Row(verticalAlignment=Alignment.CenterVertically){
                FilterChip(selected=present==true,onClick={present=true;save(Attendance(shamsiDate(),s.id,mode,true,hw))},label={Text("🟢 حاضر")})
                Spacer(Modifier.width(6.dp))
                FilterChip(selected=present==false,onClick={present=false;save(Attendance(shamsiDate(),s.id,mode,false,if(mode=="virtual")hw else null)},label={Text("🔴 غایب")})
            }
            if(mode=="virtual" && present!=null){
                Row(verticalAlignment=Alignment.CenterVertically){
                    FilterChip(selected=hw==true,onClick={hw=true;save(Attendance(shamsiDate(),s.id,mode,present!!,true))},label={Text("✅ کامل")})
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected=hw==false,onClick={hw=false;save(Attendance(shamsiDate(),s.id,mode,present!!,false))},label={Text("⚠️ ناقص")})
                }
            }
        }
    }
}

@Composable fun SmsConfirm(vm:MainVm,list:List<Student>,records:List<Attendance>,date:String,close:()->Unit) {
    val c=LocalContext.current
    val absent=list.filter{s->records.any{it.studentId==s.id && !it.present}}
    val noPhone=absent.filter{it.guardianPhone.isBlank()}
    val withPhone=absent.filter{it.guardianPhone.isNotBlank()}
    val scope=rememberCoroutineScope()
    var sending by remember{mutableStateOf(false)}

    fun sendUsingSim2() {
        if(sending) return
        sending=true
        scope.launch {
            try {
                val db=AppDb.get(c)
                val st=db.settings().observe().let{kotlinx.coroutines.flow.first(k)} ?: AppSettings()
                val subManager=c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subscriptions = subManager.activeSubscriptionInfoList.orEmpty()
                // slotIndex is zero-based: SIM 2 => slotIndex == 1.
                val sim2 = subscriptions.firstOrNull { it.simSlotIndex == 1 }
                if(sim2 == null) {
                    Toast.makeText(c,"سیم‌کارت ۲ فعال یا قابل شناسایی نیست.",Toast.LENGTH_LONG).show()
                    return@launch
                }
                val sms=SmsManager.getDefault().createForSubscriptionId(sim2.subscriptionId)
                var sentCount=0
                withPhone.forEach { s->
                    val old=records.firstOrNull{it.studentId==s.id}
                    // Do not send again when this attendance record is already marked as sent.
                    if(old?.smsSent == true) return@forEach
                    val text=st.smsTemplate.replace("[نام]",s.name).replace("[تاریخ]",date).replace("[مدرسه]",st.school)
                    if(text.length <= 160) {
                        sms.sendTextMessage(s.guardianPhone,null,text,null,null)
                    } else {
                        val parts=sms.divideMessage(text)
                        sms.sendMultipartTextMessage(s.guardianPhone,null,parts,null,null)
                    }
                    if(old!=null) db.attendance().upsert(old.copy(smsSent=true))
                    sentCount++
                }
                Toast.makeText(c,"$sentCount پیامک از سیم‌کارت ۲ ارسال شد.",Toast.LENGTH_LONG).show()
                close()
            } catch (e: SecurityException) {
                Toast.makeText(c,"دسترسی لازم برای شناسایی سیم‌کارت ۲ یا ارسال پیامک وجود ندارد.",Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(c,"ارسال پیامک از سیم‌کارت ۲ انجام نشد: ${e.message ?: "خطای نامشخص"}",Toast.LENGTH_LONG).show()
            } finally {
                sending=false
            }
        }
    }

    val permissionLauncher=androidx.activity.compose.rememberLauncherForActivityResult(
        contract=ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val smsGranted=result[Manifest.permission.SEND_SMS] == true
        val phoneGranted=result[Manifest.permission.READ_PHONE_STATE] == true
        if(smsGranted && phoneGranted) sendUsingSim2()
        else Toast.makeText(c,"برای ارسال از سیم‌کارت ۲، مجوزهای لازم را تأیید کنید.",Toast.LENGTH_LONG).show()
    }

    AlertDialog(onDismissRequest={if(!sending) close()},title={Text("${absent.size} دانش‌آموز غایب هستند")},
        text={Column{
            Text(if(withPhone.isEmpty()) "شماره‌ای برای ارسال پیامک ثبت نشده است." else "آیا پیامک غیبت برای آن‌ها از سیم‌کارت ۲ ارسال شود؟")
            if(noPhone.isNotEmpty()){Spacer(Modifier.height(8.dp));Text("شماره ثبت نشده: ${noPhone.joinToString{it.name}}",color=Color(0xFFB04A3A))}
            if(sending){Spacer(Modifier.height(8.dp));Text("در حال ارسال از سیم‌کارت ۲…",color=Color.Gray)}
        }},
        dismissButton={TextButton(enabled=!sending,onClick=close){Text("لغو")}},
        confirmButton={Button(enabled=withPhone.isNotEmpty() && !sending,onClick={
            val smsGranted=androidx.core.content.ContextCompat.checkSelfPermission(c,Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED
            val phoneGranted=androidx.core.content.ContextCompat.checkSelfPermission(c,Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED
            if(smsGranted && phoneGranted) sendUsingSim2()
            else permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS,Manifest.permission.READ_PHONE_STATE))
        }){Text("ارسال از سیم‌کارت ۲")}})
}

@Composable fun History(vm:MainVm) {
    Header("سوابق",vm)
    Column(Modifier.padding(18.dp)){Text("سوابق جلسات در پایگاه داده گوشی ذخیره می‌شوند.");Spacer(Modifier.height(12.dp));Text("برای مشاهده جزئیات، تاریخ و نوع کلاس از همین بخش قابل توسعه است.",color=Color.Gray)}
}

@Composable fun Settings(vm:MainVm) {
    val c=LocalContext.current
    val st by vm.settings.collectAsState()
    var school by remember(st.school){mutableStateOf(st.school)}
    var teacher by remember(st.teacher){mutableStateOf(st.teacher)}
    var cls by remember(st.className){mutableStateOf(st.className)}
    var sms by remember(st.smsTemplate){mutableStateOf(st.smsTemplate)}
    Header("تنظیمات",vm)
    LazyColumn(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("اطلاعات مدرسه",fontWeight=FontWeight.Bold)}
        item{OutlinedTextField(school,{school=it},Modifier.fillMaxWidth(),label={Text("نام مدرسه")})}
        item{OutlinedTextField(teacher,{teacher=it},Modifier.fillMaxWidth(),label={Text("نام معلم")})}
        item{OutlinedTextField(cls,{cls=it},Modifier.fillMaxWidth(),label={Text("نام کلاس")})}
        item{Text("تنظیمات پیامک",fontWeight=FontWeight.Bold)}
        item{OutlinedTextField(sms,{sms=it},Modifier.fillMaxWidth(),minLines=4,label={Text("متن پیامک")})}
        item{Text("متغیرهای قابل استفاده: [نام]  [تاریخ]  [مدرسه]",color=Color.Gray)}
        item{Button({vm.saveSettings(AppSettings(1,school,teacher,cls,sms));Toast.makeText(c,"ذخیره شد",Toast.LENGTH_SHORT).show()},Modifier.fillMaxWidth().height(52.dp)){Text("ذخیره تنظیمات")}}
        item{OutlinedButton({vm.exportJson(c)},Modifier.fillMaxWidth().height(52.dp)){Text("پشتیبان‌گیری اطلاعات")}}
    }
}

fun generateReport(c:Context,students:List<Student>,records:List<Attendance>,date:String) {
    val w=1200; val rowH=70; val h=180+rowH*(students.size+1)
    val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(bmp); canvas.drawColor(Color.WHITE.toArgb())
    val p=Paint(Paint.ANTI_ALIAS_FLAG); p.color=android.graphics.Color.rgb(40,60,50);p.textSize=42f;p.typeface=Typeface.DEFAULT_BOLD
    canvas.drawText("گزارش عملکرد کلاس مجازی",w-650f,60f,p)
    p.textSize=28f;p.typeface=Typeface.DEFAULT
    canvas.drawText("تاریخ: $date",w-380f,110f,p)
    val cols=listOf("ردیف","نام دانش‌آموز","حضور","تکلیف","عملکرد")
    p.textSize=26f;p.color=android.graphics.Color.DKGRAY
    cols.forEachIndexed{i,t->canvas.drawText(t,80f+i*220f,160f,p)}
    students.forEachIndexed{i,s->
        val a=records.firstOrNull{it.studentId==s.id}; val y=220f+i*rowH
        val htxt=if(a==null)"—" else if(a.present)"حاضر" else "غایب"
        val hw=if(a?.homeworkComplete==true)"کامل" else if(a?.homeworkComplete==false)"ناقص" else "—"
        val perf=when{a==null->"ثبت نشده";!a.present->"غایب";a.homeworkComplete==true->"کامل";else->"نیازمند پیگیری"}
        listOf("${i+1}",s.name,htxt,hw,perf).forEachIndexed{j,t->canvas.drawText(t,80f+j*220f,y,p)}
    }
    val values=ContentValues().apply{
        put(MediaStore.Images.Media.DISPLAY_NAME,"گزارش کلاس-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE,"image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/دفتر کلاس")
    }
    val uri=c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
    uri?.let{c.contentResolver.openOutputStream(it)?.use{out->bmp.compress(Bitmap.CompressFormat.PNG,100,out)}}
    Toast.makeText(c,"گزارش در تصاویر ذخیره شد.",Toast.LENGTH_LONG).show()
}
