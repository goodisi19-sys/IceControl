package com.icecontrol.app

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var adapter: BluetoothAdapter
    private lateinit var status: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var servicesBox: LinearLayout
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, BluetoothDevice>()
    private val logs = StringBuilder()
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val queue = ArrayDeque<BluetoothGattCharacteristic>()
    private val important = setOf("2b99", "2b9a", "2ba1", "2ba4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        buildUi()
        log("Запуск IceControl; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); устройство ${Build.MANUFACTURER} ${Build.MODEL}")
        if (adapter == null) setStatus("Bluetooth недоступен") else requestBtPermissions()
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,16,18,16) }
        content.addView(TextView(this).apply { text="IceControl"; textSize=26f })
        status = TextView(this).apply { text="Ожидание"; textSize=14f }
        content.addView(status)
        content.addView(TextView(this).apply { text="BLE-диагностика OnePlus PCV14 / Ice Card Mini. Команды записи пока не отправляются." })
        content.addView(Button(this).apply { text="Найти кулер"; setOnClickListener { startScan() } })
        content.addView(Button(this).apply { text="Отключиться"; setOnClickListener { disconnect() } })
        content.addView(section("Найденные устройства"))
        devicesBox = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; content.addView(devicesBox)
        content.addView(section("Службы и характеристики"))
        content.addView(Button(this).apply { text="Прочитать доступные характеристики"; setOnClickListener { readAll() } })
        servicesBox = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; content.addView(servicesBox)
        content.addView(section("Диагностика и логи"))
        content.addView(Button(this).apply { text="Скопировать полный лог"; setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("IceControl log", fullLog()))
            Toast.makeText(this@MainActivity,"Лог скопирован",Toast.LENGTH_SHORT).show()
        } })
        content.addView(Button(this).apply { text="Очистить журнал"; setOnClickListener { logs.clear(); logView.text="Журнал очищен"; log("Журнал начат заново") } })
        logView = TextView(this).apply { textSize=12f; setTextIsSelectable(true); setPadding(8,8,8,8) }
        content.addView(logView)
        val scroll=ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun section(s:String)=TextView(this).apply { text=s; textSize=18f; setPadding(0,18,0,6) }
    private fun setStatus(s:String) { runOnUiThread { status.text=s }; log("СТАТУС: $s") }
    private fun log(s:String) {
        val stamp=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date())
        val line="[$stamp] $s"
        synchronized(logs) { logs.append(line).append('\n') }
        runOnUiThread { if (::logView.isInitialized) logView.append(line+"\n") }
        android.util.Log.i("IceControl",s)
    }
    private fun fullLog()= "IceControl diagnostic log\nAndroid ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}\nDevice ${Build.MANUFACTURER} ${Build.MODEL}\n"+synchronized(logs){logs.toString()}

    private fun permitted() = if (Build.VERSION.SDK_INT>=31)
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED

    private fun requestBtPermissions() {
        if(permitted()){setStatus("Bluetooth готов. Нажми «Найти кулер».");return}
        val p=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        requestPermissions(p,41); log("Запрошены разрешения Bluetooth")
    }
    override fun onRequestPermissionsResult(code:Int,p:Array<out String>,r:IntArray) {
        super.onRequestPermissionsResult(code,p,r)
        if(code==41 && r.isNotEmpty() && r.all{it==PackageManager.PERMISSION_GRANTED}) setStatus("Разрешения получены")
        else if(code==41) { setStatus("Нет необходимых разрешений"); log("Пользователь не выдал разрешения Bluetooth") }
    }

    private fun startScan() {
        if(!permitted()){requestBtPermissions();return}
        if(!adapter.isEnabled){setStatus("Включи Bluetooth");return}
        try {
            found.clear(); devicesBox.removeAllViews(); scanning=true
            setStatus("Поиск BLE-устройств (12 секунд)")
            adapter.bluetoothLeScanner.startScan(scanCallback)
            handler.postDelayed({ if(scanning) stopScan("Поиск завершён; найдено: ${found.size}") },12000)
        } catch(e:Exception){ logError("Ошибка запуска сканирования",e);setStatus("Ошибка сканирования: ${e.message}") }
    }
    private fun stopScan(message:String) {
        try { adapter.bluetoothLeScanner.stopScan(scanCallback) } catch(e:Exception){logError("stopScan",e)}
        scanning=false;setStatus(message)
    }
    private val scanCallback=object:ScanCallback(){
        override fun onScanResult(type:Int,result:ScanResult) {
            val d=result.device; val address=try{d.address}catch(_:Exception){"unknown"}
            if(found.containsKey(address))return
            found[address]=d
            val name=try{d.name?:result.scanRecord?.deviceName?:"Без имени"}catch(_:Exception){"BLE"}
            log("Найдено устройство: $name, адрес скрыт, RSSI ${result.rssi} dBm")
            runOnUiThread { devicesBox.addView(Button(this@MainActivity).apply {
                text="$name • RSSI ${result.rssi} dBm";gravity=Gravity.START
                setOnClickListener{connect(d,name)}
            }) }
        }
        override fun onScanFailed(code:Int){scanning=false;log("Ошибка BLE scan, code=$code");setStatus("Ошибка сканирования: $code")}
    }
    private fun connect(d:BluetoothDevice,name:String) {
        if(!permitted()){requestBtPermissions();return}
        try {
            if(scanning)stopScan("Подключение…")
            gatt?.close();servicesBox.removeAllViews();setStatus("Подключение к $name")
            gatt=d.connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE)
            log("Запрошено GATT-подключение к $name")
        }catch(e:Exception){logError("Ошибка подключения",e);setStatus("Ошибка подключения: ${e.message}")}
    }
    private fun disconnect() {
        try{gatt?.disconnect();gatt?.close()}catch(e:Exception){logError("Ошибка отключения",e)}
        gatt=null;setStatus("Отключено")
    }
    private val callback=object:BluetoothGattCallback(){
        override fun onConnectionStateChange(g:BluetoothGatt,code:Int,state:Int) {
            log("GATT connection state: status=$code, state=$state")
            if(state==BluetoothProfile.STATE_CONNECTED && code==BluetoothGatt.GATT_SUCCESS){
                setStatus("Подключено; поиск служб");try{g.discoverServices()}catch(e:Exception){logError("discoverServices",e)}
            } else if(state==BluetoothProfile.STATE_DISCONNECTED)setStatus("Отключено от кулера")
        }
        override fun onServicesDiscovered(g:BluetoothGatt,code:Int) {
            if(code!=BluetoothGatt.GATT_SUCCESS){log("Ошибка получения GATT-служб: status=$code");return}
            runOnUiThread {
                servicesBox.removeAllViews()
                g.services.forEach { service ->
                    servicesBox.addView(section("Служба: ${service.uuid}"))
                    service.characteristics.forEach { c ->
                        val id=c.uuid.toString()
                        val props=mutableListOf<String>()
                        if(c.properties and BluetoothGattCharacteristic.PROPERTY_READ!=0)props+="READ"
                        if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE!=0)props+="WRITE"
                        if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0)props+="WRITE_NO_RESPONSE"
                        if(c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0)props+="NOTIFY"
                        if(c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE!=0)props+="INDICATE"
                        servicesBox.addView(TextView(this@MainActivity).apply{text="$id\n${props.joinToString()}";textSize=12f})
                        if(c.properties and BluetoothGattCharacteristic.PROPERTY_READ!=0)
                            servicesBox.addView(Button(this@MainActivity).apply{
                                text=if(id.substring(4,8) in important)"Прочитать (важная)" else "Прочитать"
                                setOnClickListener{readOne(c)}
                            })
                    }
                }
            }
            setStatus("Служб найдено: ${g.services.size}");log("GATT services discovered: ${g.services.size}")
        }
        override fun onCharacteristicRead(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray,code:Int) {
            log("READ ${c.uuid}: status=$code HEX=[${value.joinToString(" "){ "%02X".format(it) }}]")
            if(queue.isNotEmpty())handler.postDelayed({readNext()},300)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g:BluetoothGatt,c:BluetoothGattCharacteristic,code:Int) {
            val v=c.value?:byteArrayOf();log("READ ${c.uuid}: status=$code HEX=[${v.joinToString(" "){ "%02X".format(it) }}]")
            if(queue.isNotEmpty())handler.postDelayed({readNext()},300)
        }
    }
    private fun readOne(c:BluetoothGattCharacteristic) {
        try {
            val result=if(Build.VERSION.SDK_INT>=33)gatt?.readCharacteristic(c)?:false else {
                @Suppress("DEPRECATION")
                gatt?.readCharacteristic(c)?:false
            }
            log("Запрос READ ${c.uuid}: accepted=$result")
        }catch(e:Exception){logError("Ошибка чтения ${c.uuid}",e)}
    }
    private fun readAll() {
        val g=gatt
        if(g==null){Toast.makeText(this,"Сначала подключись к кулеру",Toast.LENGTH_SHORT).show();return}
        queue.clear();g.services.flatMap{it.characteristics}.filter{it.properties and BluetoothGattCharacteristic.PROPERTY_READ!=0}.forEach{queue.add(it)}
        log("Начато последовательное чтение: ${queue.size} характеристик");readNext()
    }
    private fun readNext() {
        val c=queue.pollFirst()?:return
        try {
            val ok=if(Build.VERSION.SDK_INT>=33)gatt?.readCharacteristic(c)==true else {
                @Suppress("DEPRECATION")
                gatt?.readCharacteristic(c)==true
            }
            log("READ queued ${c.uuid}: accepted=$ok")
            if(!ok)handler.postDelayed({readNext()},350)
        }catch(e:Exception){logError("Ошибка очереди чтения",e);handler.postDelayed({readNext()},350)}
    }
    private fun logError(label:String,e:Throwable) {
        log("ОШИБКА: $label: ${e.javaClass.simpleName}: ${e.message}")
        e.stackTrace.take(12).forEach{log("  at $it")}
    }
    override fun onDestroy() {
        super.onDestroy()
        try{if(scanning)adapter.bluetoothLeScanner.stopScan(scanCallback)}catch(_:Exception){}
        try{gatt?.close()}catch(_:Exception){}
    }
}
