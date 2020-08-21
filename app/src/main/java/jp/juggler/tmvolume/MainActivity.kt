package jp.juggler.tmvolume

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import com.google.android.flexbox.FlexboxLayout
import com.illposed.osc.*
import com.illposed.osc.transport.udp.OSCPortIn
import com.illposed.osc.transport.udp.OSCPortOut
import jp.juggler.tmvolume.FaderVol.toFader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.launch
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ScopedActivity() {

    companion object {
        const val TAG = "TMVolume"

        const val PREF_SHOW_CONNECTION_SETTINGS = "ShowConnectionSettings"
        const val PREF_CLIENT_PORT = "ClientPort"
        const val PREF_SERVER_ADDR = "ServerAddr"
        const val PREF_SERVER_PORT = "ServerPort"
        const val PREF_OBJECT_ADDR = "ObjectAddr"
        const val PREF_VOLUME = "Volume"
        const val PREF_BUS = "Bus"
        const val PREF_PRESETS = "Presets"

        const val SEEKBAR_MIN = 0
        const val SEEKBAR_MAX = 143

        private fun Int.progressToDb(): Float = 6f - (SEEKBAR_MAX - this) * 0.5f

        private fun Float.dbToProgress(): Int = (SEEKBAR_MAX + ((this - 6f) * 2f).toInt())
            .clip(SEEKBAR_MIN, SEEKBAR_MAX)

        private fun Float.formatDb() = when {
            this < -65f -> "-∞"
            else -> String.format("%.1f", this)
        }

        private val reVolumeVal = """([-+]?[\d.]+)\s*dB\b""".toRegex(RegexOption.IGNORE_CASE)

        fun String?.notEmpty() = if (this?.isNotEmpty() == true) this else null

        fun Int.clip(min: Int, max: Int) = if (this < min) min else if (this > max) max else this

        fun avg(a: Int, b: Int) = (a + b) / 2

        var View.isEnabledAlpha: Boolean
            get() = isEnabled
            set(value) {
                if (value == isEnabled) return
                isEnabled = value
                alpha = if (value) 1.0f else 0.3f
            }

        fun View.vg(shown: Boolean) =
            shown.also { visibility = if (it) View.VISIBLE else View.GONE }
    }

    private lateinit var swShowConnectionSettings: Switch
    private lateinit var rowClient: TableRow
    private lateinit var rowServer: TableRow


    private lateinit var tvClientAddr: TextView
    private lateinit var etClientPort: EditText
    private lateinit var etServerAddr: EditText
    private lateinit var etServerPort: EditText
    private lateinit var etObjectAddress: EditText
    private lateinit var rbBusInput: RadioButton
    private lateinit var rbBusPlayback: RadioButton
    private lateinit var rbBusOutput: RadioButton

    private lateinit var sbVolume: SeekBar
    private lateinit var tvVolume: TextView
    private lateinit var btnMinus: ImageButton
    private lateinit var btnPlus: ImageButton

    private lateinit var flPresets: FlexboxLayout
    private lateinit var btnSave: ImageButton

    private lateinit var tvMap: TextView

    private var restoreBusy = false
    private lateinit var pref: SharedPreferences
    private lateinit var handler: Handler
    private val channel = Channel<Message>(capacity = CONFLATED)

    private var presets = mutableListOf<Int>()

    private var lastSeekbarEdit = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handler = Handler()
        pref = getSharedPreferences("pref", Context.MODE_PRIVATE)

        restoreBusy = true
        initUi()

        if (savedInstanceState == null) {
            loadPref()
            restoreBusy = false
        }

        showVolumeNumber()

        startWorker()

    }

    override fun onResume() {


        super.onResume()

        showMyAddress()

        startListen()

        send(objectAddr = "/", value = 1f)
    }

    override fun onPause() {
        super.onPause()
        stopListen()
        handler.removeCallbacks(procShowMap)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreBusy = false
        showVolumeNumber()
    }

    ///////////////////////////////////////////////////////

    private fun EditText.addSaver(key: String, callback: (String) -> Unit = {}) =
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) = Unit
            override fun onTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) = Unit
            override fun afterTextChanged(p0: Editable) {
                if (restoreBusy) return
                val sv = text.toString()
                pref.edit().putString(key, sv).apply()
                callback(sv)
            }
        })

    private fun CompoundButton.addSaver(key: String, value: Int) =
        setOnCheckedChangeListener { _, isChecked ->
            if (restoreBusy) return@setOnCheckedChangeListener
            if (isChecked) pref.edit().putInt(key, value).apply()
        }

    private fun CompoundButton.addSaver(key: String, callback: (Boolean) -> Unit = {}) =
        setOnCheckedChangeListener { _, isChecked ->
            if (restoreBusy) return@setOnCheckedChangeListener
            pref.edit().putBoolean(key, isChecked).apply()
            callback(isChecked)
        }

    private fun initUi() {

        setContentView(R.layout.activity_main)
        supportActionBar?.apply {
            setSubtitle(R.string.header_subtitle)

            setDisplayUseLogoEnabled(true)
            setLogo(R.drawable.header_logo)
            // 最後に呼び出す
            setDisplayShowHomeEnabled(true)
        }

        swShowConnectionSettings = findViewById(R.id.swShowConnectionSettings)
        rowClient = findViewById(R.id.rowClient)
        rowServer = findViewById(R.id.rowServer)

        tvClientAddr = findViewById(R.id.tvClientAddr)
        etClientPort = findViewById(R.id.etClientPort)
        etServerAddr = findViewById(R.id.etServerAddr)
        etServerPort = findViewById(R.id.etServerPort)
        etObjectAddress = findViewById(R.id.etObjectAddress)
        sbVolume = findViewById(R.id.sbVolume)

        tvVolume = findViewById(R.id.tvVolume)

        rbBusInput = findViewById(R.id.rbBusInput)
        rbBusPlayback = findViewById(R.id.rbBusPlayback)
        rbBusOutput = findViewById(R.id.rbBusOutput)

        tvMap = findViewById(R.id.tvMap)

        btnMinus = findViewById(R.id.btnMinus)
        btnPlus = findViewById(R.id.btnPlus)

        flPresets = findViewById(R.id.flPresets)
        btnSave = findViewById(R.id.btnSave)

        etClientPort.addSaver(PREF_CLIENT_PORT) { startListen() }
        etServerAddr.addSaver(PREF_SERVER_ADDR)
        etServerPort.addSaver(PREF_SERVER_PORT)
        etObjectAddress.addSaver(PREF_OBJECT_ADDR)

        rbBusInput.addSaver(PREF_BUS, 0)
        rbBusPlayback.addSaver(PREF_BUS, 1)
        rbBusOutput.addSaver(PREF_BUS, 2)

        swShowConnectionSettings.addSaver(PREF_SHOW_CONNECTION_SETTINGS) { showConnectionSettings() }

        sbVolume.min = SEEKBAR_MIN
        sbVolume.max = SEEKBAR_MAX
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(var1: SeekBar) = Unit
            override fun onStopTrackingTouch(var1: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (restoreBusy) return
                if (fromUser) lastSeekbarEdit = SystemClock.elapsedRealtime()
                pref.edit().putInt(PREF_VOLUME, progress).apply()
                showVolumeNumber()
                send()
            }
        })

        btnPlus.setOnClickListener { setVolume(sbVolume.progress + 1) }
        btnMinus.setOnClickListener { setVolume(sbVolume.progress - 1) }
        btnSave.setOnClickListener { addPreset() }
    }


    private fun loadPref() {
        etClientPort.setText(pref.getString(PREF_CLIENT_PORT, "9001"))
        etServerAddr.setText(pref.getString(PREF_SERVER_ADDR, ""))
        etServerPort.setText(pref.getString(PREF_SERVER_PORT, "7001"))
        etObjectAddress.setText(pref.getString(PREF_OBJECT_ADDR, "/1/volume1"))
        sbVolume.progress = pref.getInt(PREF_VOLUME, avg(SEEKBAR_MIN, SEEKBAR_MAX))
            .clip(SEEKBAR_MIN, SEEKBAR_MAX)

        presets =
            pref.getString(PREF_PRESETS, "")!!.split(",").mapNotNull { it.toIntOrNull() }.sorted()
                .toMutableList()
        showPresets()

        swShowConnectionSettings.isChecked = pref.getBoolean(PREF_SHOW_CONNECTION_SETTINGS, true)
        showConnectionSettings()

        when (pref.getInt(PREF_BUS, 0)) {
            1 -> rbBusPlayback
            2 -> rbBusOutput
            else -> rbBusInput
        }.isChecked = true
    }

    private fun showConnectionSettings() {
        val isShown = swShowConnectionSettings.isChecked
        rowClient.vg(isShown)
        rowServer.vg(isShown)
    }


    private fun showPresets() {
        (0 until flPresets.childCount).reversed().forEach { i ->
            val child = flPresets.getChildAt(i)
            if (child is Button) {
                flPresets.removeViewAt(i)
            }
        }
        val w = (resources.displayMetrics.density * 40f).toInt()
        val h = (resources.displayMetrics.density * 40f).toInt()
        for (progress in presets) {
            flPresets.addView(Button(this).apply {
                layoutParams = FlexboxLayout.LayoutParams(
                    FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    h
                ).apply {}
                text = progress.progressToDb().formatDb()
                minWidth = w
                minimumWidth = w
                setOnClickListener { sbVolume.progress = progress }
                setOnLongClickListener {
                    removePreset(progress)
                    true
                }
            })
        }
    }

    private fun addPreset() {
        val progress = sbVolume.progress
        if (presets.contains(progress)) return
        presets.add(progress)
        presets.sort()
        pref.edit().putString(PREF_PRESETS, presets.joinToString(",")).apply()
        showPresets()
        showVolumeNumber()
    }

    private fun removePreset(progress: Int) {
        presets.remove(progress)
        pref.edit().putString(PREF_PRESETS, presets.joinToString(",")).apply()
        showPresets()
        showVolumeNumber()
    }

    private fun setVolume(newProgress: Int) {
        val oldVal = sbVolume.progress
        val newVal = newProgress.clip(SEEKBAR_MIN, SEEKBAR_MAX)
        if (newVal != oldVal) sbVolume.progress = newVal
    }

    @SuppressLint("SetTextI18n")
    private fun showVolumeNumber() {
        tvVolume.text = "${sbVolume.progress.progressToDb().formatDb()} dB"
        btnMinus.isEnabledAlpha = sbVolume.progress != SEEKBAR_MIN
        btnPlus.isEnabledAlpha = sbVolume.progress != SEEKBAR_MAX
        btnSave.isEnabledAlpha = !presets.contains(sbVolume.progress)
    }

    private fun getFaderValue() =
        sbVolume.progress.progressToDb().toFader().toFloat()

    private fun showError(msg: String) {
        if (!Looper.getMainLooper().isCurrentThread) {
            handler.post { showError(msg) }
            return
        }
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    class Message(
        val serverAddr: String,
        val serverPort: Int,
        val busAddr: String,
        val objectAddr: String,
        val args: ArrayList<Any>
    )

    private fun send(
        serverAddr: String? = etServerAddr.text.toString().trim().notEmpty(),
        serverPort: Int? = etServerPort.text.toString().trim().notEmpty()?.toIntOrNull(),
        busAddr: String = when {
            rbBusPlayback.isChecked -> "/1/busPlayback"
            rbBusOutput.isChecked -> "/1/busOutput"
            else -> "/1/busInput"
        },
        objectAddr: String? = etObjectAddress.text.toString().trim().notEmpty(),
        value: Float = getFaderValue()
    ) {
        val args = arrayListOf<Any>(value)

        if (serverAddr == null) {
            showError("missing server addr")
            return
        }
        if (serverPort == null) {
            showError("missing server port")
            return
        }
        if (objectAddr == null) {
            showError("missing object addr")
            return
        }

        launch(activityJob + Dispatchers.IO) {
            channel.send(
                Message(
                    serverAddr = serverAddr,
                    serverPort = serverPort,
                    busAddr = busAddr,
                    objectAddr = objectAddr,
                    args = args
                )
            )
        }
    }

    private fun startWorker() = launch(activityJob + Dispatchers.IO) {
        while (true) {
            try {
                val item = channel.receive()
                val port = OSCPortOut(InetAddress.getByName(item.serverAddr), item.serverPort)
                try {
                    port.send(OSCMessage(item.busAddr, arrayListOf<Any>(1.0f)))
                    port.send(OSCMessage(item.objectAddr, item.args))
                } finally {
                    try {
                        port.close()
                    } catch (ex: IOException) {
                    }
                }
            } catch (ex: CancellationException) {
                break
            } catch (ex: Throwable) {
                showError("${ex.javaClass.simpleName} : ${ex.message}")
            }
        }
    }

    private fun showMyAddress() {
        tvClientAddr.text = try {
            val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
            InetAddress.getByAddress(
                BigInteger.valueOf(
                    wm.connectionInfo.ipAddress.toLong()
                )
                    .toByteArray()
                    .reversedArray()
            ).hostAddress
        } catch (ex: Throwable) {
            ex.printStackTrace()
            "?"
        }
    }

    private var objectMap = ConcurrentHashMap<String, String>()


    private val procShowMap: Runnable = Runnable {
        tvMap.text = StringBuilder().apply {
            objectMap.keys().toList().sorted().forEach {
                if (isNotEmpty()) append("\n")
                append("${it}=${objectMap[it]}")
            }
        }.toString()

        val strVolumeVal = (objectMap["${etObjectAddress.text}Val"] ?: "?").trim(',')
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekbarEdit >= 1000L) {
            val progress =
                reVolumeVal.find(strVolumeVal)
                    ?.groupValues?.get(1)?.toFloatOrNull()?.dbToProgress()
                    ?: if (strVolumeVal.contains("∞")) 0 else null
            if (progress != null && progress != sbVolume.progress) sbVolume.progress = progress
        }
    }

    private fun fireShowMap() {
        handler.removeCallbacks(procShowMap)
        handler.postDelayed(procShowMap, 333L)
    }

    private fun OSCPacket.dump(dstMap: ConcurrentHashMap<String, String>) {
        when (this) {
            is OSCMessage -> {
                // Log.d(TAG, "Message addr=${address}")
                val sb = StringBuilder()
                arguments?.forEach {
                    sb.append(',')
                    sb.append(String.format("%s", it))
                }
                dstMap[address] = sb.toString()
            }
            is OSCBundle -> {
                // Log.d(TAG, "Bundle")
                packets?.forEach { it?.dump(dstMap) }
            }
        }
    }

    private var receiver: OSCPortIn? = null

    private fun stopListen() {
        try {
            receiver?.stopListening()
        } catch (ex: Throwable) {
            ex.printStackTrace()
        }
        receiver = null
        objectMap = ConcurrentHashMap()
    }

    private fun startListen() {
        try {
            receiver?.stopListening()
        } catch (ex: Throwable) {
            ex.printStackTrace()
        }
        objectMap = ConcurrentHashMap()
        fireShowMap()
        receiver = try {
            val port = etClientPort.text.toString().trim().toIntOrNull()
            if (port == null || port <= 1024) null else OSCPortIn(port).apply {
                addPacketListener(object : OSCPacketListener {
                    override fun handleBadData(event: OSCBadDataEvent?) = Unit
                    override fun handlePacket(event: OSCPacketEvent?) {
                        val map = ConcurrentHashMap<String, String>()
                        event?.packet?.dump(map)
                        for (i in 1..8) {
                            val va = map["/1/volume$i"]?.trim(',')
                            val vb = map["/1/volume${i}Val"]
                            if (va == null || vb == null) continue
                            if (va == "0.0") continue
                            Log.d(TAG, "volumeVal $va $vb")
                        }
                        for (entry in map.entries) {
                            objectMap[entry.key] = entry.value
                        }
                        fireShowMap()
                    }
                })
                startListening()
            }
        } catch (ex: Throwable) {
            ex.printStackTrace()
            showError("${ex.javaClass.simpleName} : ${ex.message}")
            null
        }
    }
}

/*

TotalMixのOSCパラメータ一覧
An OSC implementation chart can be downloaded from the RME website:
http://www.rme-audio.de/download/osc_table_totalmix.zip

ボリューム指定の前にバス選択メッセージ、以下のいずれかを送る。引数は無視される
/1/busInput 1.0
/1/busPlayback 1.0
/1/busOutput 1.0

次にボリュームメッセージを送る。
末尾のチャネル番号は各バスのTotalMix上の表示順。stereo/mono切り替えの有無で割り当てが変わる
/1/volume1 ～ /1/volume6 (num)
(num)はフェーダーの値を入れる。カーブがあるらしくそのままdbに変換することはできない

TotalMax のOptions-SettingsのOSCタブの Remote Controller Address と Port Outgoing に設定したホストとアドレスに



*/