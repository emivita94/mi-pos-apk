package com.ampersand.pos;

import android.content.Context;
import android.os.Build;
import java.io.FileOutputStream;

/**
 * Impresión en terminales Sunmi V1/V2/V2 Pro/T2.
 * Sunmi expone la impresora interna como un archivo de dispositivo: /dev/ttyS4 o /dev/ttyS1
 * No requiere el Sunmi SDK — funciona directo con ESC/POS.
 */
public class SunmiPrinter {

    // Rutas conocidas de la impresora interna según modelo
    private static final String[] PRINTER_PATHS = {
        "/dev/ttyS4",    // Sunmi V2 / V2 Pro
        "/dev/ttyS1",    // Sunmi V1
        "/dev/ttyMT2",   // Algunos modelos genéricos
        "/dev/ttyUSB0",  // Vizzion y terminales genéricas con impresora USB interna
    };

    public static boolean print(Context context, byte[] data) {
        // Intentar cada ruta conocida
        for (String path : PRINTER_PATHS) {
            if (printToPath(path, data)) {
                android.util.Log.d("SunmiPrinter", "Impreso via: " + path);
                return true;
            }
        }

        // Fallback: intentar detectar el modelo y usar la ruta correcta
        String model = Build.MODEL.toLowerCase();
        if (model.contains("sunmi") || model.contains("v2") || model.contains("t2")) {
            // Modelos Sunmi conocidos
            return printToPath("/dev/ttyS4", data);
        }

        return false;
    }

    private static boolean printToPath(String path, byte[] data) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(path);
            // Enviar en chunks para no saturar el buffer
            int CHUNK = 512;
            for (int i = 0; i < data.length; i += CHUNK) {
                int len = Math.min(CHUNK, data.length - i);
                fos.write(data, i, len);
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("SunmiPrinter", "No se pudo imprimir en " + path + ": " + e.getMessage());
            return false;
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }
}
