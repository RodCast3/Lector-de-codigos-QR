package com.example.qr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import androidx.camera.core.ExperimentalGetImage
import androidx.annotation.OptIn

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var previewView: PreviewView
    private var escaneoActivo = true
    private val serverUrl = "http://192.168.100.66:5000/procesar_palabra"
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)

        // Configurar el escáner de códigos de barras/QR
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Solicitar permisos de cámara
        if (verificarPermisos()) {
            iniciarCamara()
        } else {
            solicitudPermisos.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }


    private fun verificarPermisos() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }


    @OptIn(ExperimentalGetImage::class)
    private fun iniciarCamara() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Configurar la vista previa
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Configurar el analizador de imágenes
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage, imageProxy.imageInfo.rotationDegrees)

                            // Procesar la imagen para detectar QR
                            barcodeScanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        if (escaneoActivo) {
                                            escaneoActivo = false
                                            val value = barcode.rawValue ?: "No se pudo leer"
                                            runOnUiThread {
                                                Toast.makeText(this, "¡QR leído!", Toast.LENGTH_SHORT).show()
                                                enviarPalabraAlServidor(value)
                                            }

                                            // Esperar 5 segundos antes de volver a activar el escaneo
                                            Handler(mainLooper).postDelayed({
                                                escaneoActivo = true
                                            }, 5000)
                                            break // salir después del primer código leído
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.e("MainActivity", "Error en la detección de QR", e)
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }
                    })
                }

            // Seleccionar la cámara frontal
            val tipoCamara = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, tipoCamara, preview, imageAnalyzer)
            } catch(exc: Exception) {
                Toast.makeText(this, "Error al iniciar cámara: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun enviarPalabraAlServidor(palabra: String) {
        val json = JSONObject().apply {
            put("palabra", palabra)
        }

        val body = json.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error al enviar palabra: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("TEXTO", "Error", e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respuesta = response.body?.string()
                runOnUiThread {
                    if (respuesta != null) {
                        val jsonRespuesta = JSONObject(respuesta)
                        val mensaje = """
                             ${jsonRespuesta.getString("procesada")}
                        """.trimIndent()
                        Toast.makeText(this@MainActivity, "Respuesta: $mensaje", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    
    private val solicitudPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            iniciarCamara()
        } else {
            Toast.makeText(this, "Se necesita aceptar los permisos de camara", Toast.LENGTH_LONG).show()
        }
    }

    //Permiso de camara
    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}