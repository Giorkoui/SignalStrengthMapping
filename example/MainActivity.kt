package com.example.signalstrengthmapper

import android.telephony.SmsManager
import java.net.HttpURLConnection
import java.net.URL
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import java.io.File
import java.io.FileWriter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.example.signalstrengthmapper.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var database: SignalDatabase
    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var telephonyManager: TelephonyManager
    private var signalStrengthValue: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = SignalDatabase.getDatabase(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        requestPermissions()

        telephonyManager.listen(object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                super.onSignalStrengthsChanged(signalStrength)
                signalStrengthValue = signalStrength?.level ?: 0
            }
        }, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)

        // 🚀 Set up click and long click listeners on FAB
        binding.fab.setOnClickListener {
            collectAndShowSignal()
        }

        binding.fab.setOnLongClickListener {
            exportToCsv()
            true
        }
    }


    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        ActivityCompat.requestPermissions(this, permissions, 1)
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.SEND_SMS),
            1)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
    }

    private fun collectAndShowSignal() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    val marker = MarkerOptions()
                        .position(latLng)
                        .title("Signal: $signalStrengthValue")
                        .icon(
                            BitmapDescriptorFactory.defaultMarker(
                                if (signalStrengthValue > 3)
                                    BitmapDescriptorFactory.HUE_GREEN
                                else
                                    BitmapDescriptorFactory.HUE_RED
                            )
                        )
                    mMap.addMarker(marker)
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))

                    // Save into database
                    saveSignalToDatabase(it.latitude, it.longitude, signalStrengthValue)

                    // Upload to ThingSpeak
                    uploadToThingSpeak(signalStrengthValue, it.latitude, it.longitude)

                    // Send SMS if critical
                    if (signalStrengthValue <= 1) {
                        sendCriticalSms(it.latitude, it.longitude)
                    }
                }
            }
        }
    }


    private fun saveSignalToDatabase(latitude: Double, longitude: Double, signalStrength: Int) {
        val signalEntry = SignalEntry(
            latitude = latitude,
            longitude = longitude,
            signalStrength = signalStrength
        )
        lifecycleScope.launch {
            database.signalDao().insert(signalEntry)
        }
    }

    private fun sendEmailWithCsv(csvFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            csvFile
        )

        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("")) // You can pre-fill a recipient if you want
            putExtra(Intent.EXTRA_SUBJECT, "Signal Strength Data Export")
            putExtra(Intent.EXTRA_TEXT, "Attached is the exported signal strength data collected by the Mobile Signal Strength Mapper App.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(emailIntent, "Send email via:"))
    }


    private fun exportToCsv() {
        lifecycleScope.launch {
            val signals = database.signalDao().getAllSignals()
            if (signals.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "No data to export", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val csvFile = File(getExternalFilesDir(null), "signal_data.csv")
                val writer = FileWriter(csvFile)
                writer.append("Latitude,Longitude,SignalStrength\n")

                for (signal in signals) {
                    writer.append("${signal.latitude},${signal.longitude},${signal.signalStrength}\n")
                }

                writer.flush()
                writer.close()

                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Export Successful")
                        .setMessage("Your signal data has been exported successfully to:\n\n${csvFile.absolutePath}\n\nDo you want to send it via Email?")
                        .setPositiveButton("Yes") { dialog, _ ->
                            sendEmailWithCsv(csvFile)
                            dialog.dismiss()
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error exporting CSV", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun uploadToThingSpeak(signalStrength: Int, latitude: Double, longitude: Double) {
        val apiKey = "P4INNRL9X8BF8IO1" // Replace with your API Key
        val urlString = "https://api.thingspeak.com/update?api_key=$apiKey&field1=$signalStrength&field2=$latitude&field3=$longitude"

        Thread {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val responseCode = connection.responseCode
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendCriticalSms(latitude: Double, longitude: Double) {
        val smsManager = SmsManager.getDefault()
        val phoneNumber = "96289545" // Replace with your real number
        val message = "⚠️ Critical signal detected! Location: https://maps.google.com/?q=$latitude,$longitude"

        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
    }


}
