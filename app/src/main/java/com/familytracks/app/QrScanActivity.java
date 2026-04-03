package com.familytracks.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera activity that scans a QR code containing server connection info.
 */
public class QrScanActivity extends AppCompatActivity
{
    private static final String TAG = "QrScanActivity";
    private static final int REQUEST_CAMERA = 100;

    private PreviewView thePreviewView;
    private TextView theStatusText;
    private ExecutorService theCameraExecutor;
    private boolean theScanned;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        thePreviewView = findViewById(R.id.previewView);
        theStatusText = findViewById(R.id.scanStatus);
        theCameraExecutor = Executors.newSingleThreadExecutor();
        theScanned = false;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
        {
            startCamera();
        }
        else
        {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA)
        {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            {
                startCamera();
            }
            else
            {
                Toast.makeText(this, "Camera permission is needed to scan QR codes",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera()
    {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    ProcessCameraProvider provider = future.get();
                    bindCamera(provider);
                }
                catch (Exception e)
                {
                    Log.e(TAG, "Camera init failed: " + e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider provider)
    {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(thePreviewView.getSurfaceProvider());

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();

        BarcodeScanner scanner = BarcodeScanning.getClient(options);

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(theCameraExecutor, new ImageAnalysis.Analyzer()
        {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy)
            {
                if (theScanned)
                {
                    imageProxy.close();
                    return;
                }

                @SuppressWarnings("UnsafeOptInUsageError")
                InputImage image = InputImage.fromMediaImage(
                        imageProxy.getImage(),
                        imageProxy.getImageInfo().getRotationDegrees()
                );

                scanner.process(image)
                        .addOnSuccessListener(barcodes ->
                        {
                            for (Barcode barcode : barcodes)
                            {
                                String value = barcode.getRawValue();
                                if (value != null && !theScanned)
                                {
                                    theScanned = true;
                                    onQrScanned(value);
                                }
                            }
                        })
                        .addOnCompleteListener(task -> imageProxy.close());
            }
        });

        provider.unbindAll();
        provider.bindToLifecycle(this, selector, preview, analysis);
    }

    private void onQrScanned(String qrContent)
    {
        Log.i(TAG, "QR scanned: " + qrContent);

        ServerConfig config = new ServerConfig();
        boolean ok = config.parseQrJson(qrContent);

        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if (ok)
                {
                    config.save(QrScanActivity.this);
                    theStatusText.setText("Connected to " + config.getHost()
                            + ":" + config.getPort());
                    Toast.makeText(QrScanActivity.this, "Server configured!",
                            Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                }
                else
                {
                    theStatusText.setText("Invalid QR code. Try again.");
                    theScanned = false;
                }
            }
        });
    }

    @Override
    protected void onDestroy()
    {
        theCameraExecutor.shutdown();
        super.onDestroy();
    }
}
