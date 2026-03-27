package com.ampersand.pos;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.view.KeyEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String TAG = "AmpersandPOS";
    private static final String APP_URL = "https://mi-pos.emvitta.workers.dev";
    // UUID SPP estándar para IposPrinter
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private WebView webView;
    private BluetoothSocket btSocket;
    private BluetoothDevice iposPrinter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        // Configurar WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Exponer el puente AndroidPrint al JavaScript
        webView.addJavascriptInterface(new PrinterBridge(), "AndroidPrint");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Buscar IposPrinter al iniciar
        buscarIposPrinter();

        // Cargar la app
        webView.loadUrl(APP_URL);
    }

    // Buscar IposPrinter entre los dispositivos BT emparejados
    private void buscarIposPrinter() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            for (BluetoothDevice device : paired) {
                String name = device.getName();
                if (name != null && (name.equalsIgnoreCase("IposPrinter")
                        || name.toLowerCase().contains("ipos")
                        || name.toLowerCase().contains("pos58"))) {
                    iposPrinter = device;
                    Log.d(TAG, "IposPrinter encontrada: " + name);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error buscando impresora: " + e.getMessage());
        }
    }

    // Conectar a IposPrinter via Bluetooth
    private boolean conectar() {
        if (iposPrinter == null) {
            buscarIposPrinter();
            if (iposPrinter == null) return false;
        }
        try {
            if (btSocket != null && btSocket.isConnected()) return true;
            btSocket = iposPrinter.createRfcommSocketToServiceRecord(SPP_UUID);
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
            btSocket.connect();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error conectando: " + e.getMessage());
            btSocket = null;
            return false;
        }
    }

    // Puente JavaScript → Android → IposPrinter
    class PrinterBridge {

        // Imprimir bytes ESC/POS en Base64
        @JavascriptInterface
        public String print(String base64Data) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                if (!conectar()) return "ERROR: No se pudo conectar a IposPrinter";
                OutputStream out = btSocket.getOutputStream();
                out.write(bytes);
                out.flush();
                return "OK";
            } catch (Exception e) {
                Log.e(TAG, "Error imprimiendo: " + e.getMessage());
                btSocket = null;
                return "ERROR: " + e.getMessage();
            }
        }

        // Listar impresoras BT emparejadas
        @JavascriptInterface
        public String getPairedBtPrinters() {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) return "[]";
                Set<BluetoothDevice> paired = adapter.getBondedDevices();
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (BluetoothDevice d : paired) {
                    if (!first) sb.append(",");
                    sb.append("{\"name\":\"").append(d.getName()).append("\",")
                      .append("\"address\":\"").append(d.getAddress()).append("\"}");
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        // Seleccionar impresora por nombre
        @JavascriptInterface
        public void setBluetoothDevice(String name) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) return;
                Set<BluetoothDevice> paired = adapter.getBondedDevices();
                for (BluetoothDevice d : paired) {
                    if (name.equals(d.getName())) {
                        iposPrinter = d;
                        // Cerrar socket anterior
                        if (btSocket != null) {
                            try { btSocket.close(); } catch (Exception ignored) {}
                            btSocket = null;
                        }
                        Log.d(TAG, "Impresora seleccionada: " + name);
                        return;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error seleccionando impresora: " + e.getMessage());
            }
        }
    }

    // Botón atrás navega en el WebView
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (btSocket != null) {
            try { btSocket.close(); } catch (Exception ignored) {}
        }
    }
}
