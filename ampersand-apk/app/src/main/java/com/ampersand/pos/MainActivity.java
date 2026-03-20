package com.ampersand.pos;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String POS_URL = "https://mi-pos.emvitta.workers.dev";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Pantalla completa sin barra de título
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        // Configurar WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);        // localStorage
        settings.setDatabaseEnabled(true);          // IndexedDB (Dexie)
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(
            settings.getUserAgentString() + " AmpersandPOS/1.0"
        );

        // Inyectar el puente de impresión — accesible como window.AndroidPrint en JS
        PrintBridge printBridge = new PrintBridge(this);
        webView.addJavascriptInterface(printBridge, "AndroidPrint");

        // Evitar que links externos abran Chrome
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // Permitir navegación interna dentro del POS
                if (url.startsWith("https://mi-pos.emvitta.workers.dev") ||
                    url.startsWith("https://kmreiniqgcvqgdtzvmel.supabase.co")) {
                    return false;
                }
                // Links externos — abrir en Chrome
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                // Sin conexión — mostrar página offline del SW
                view.loadUrl("about:blank");
                view.loadUrl("javascript:document.body.innerHTML='<div style=\"font-family:sans-serif;text-align:center;padding:40px;color:#ccc\"><h2>Sin conexión</h2><p>Verificá tu conexión a internet</p><button onclick=\"window.location.reload()\" style=\"padding:12px 24px;background:#4caf50;color:#fff;border:none;border-radius:8px;font-size:16px;cursor:pointer\">Reintentar</button></div>'");
            }
        });

        // Soporte para alerts y confirms de JS
        webView.setWebChromeClient(new WebChromeClient());

        // Cargar el POS
        webView.loadUrl(POS_URL);
    }

    // Botón atrás navega en el historial de la WebView
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // Mantener sesión al rotar pantalla
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }
}
