package com.ampersand.pos;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Puente entre JavaScript y la impresora.
 * En JS tu PWA llama: window.AndroidPrint.print(base64bytes)
 *
 * Soporta:
 *   - Impresora interna de terminales (Vizzion, Sunmi, etc.) via USB interno
 *   - Bluetooth Classic (SPP) — el que Web Bluetooth NO puede usar
 *   - USB externo via OTG
 */
public class PrintBridge {

    private final Context context;
    private static final UUID SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Nombre de la impresora BT guardado (se setea desde JS)
    private String savedBtName = null;

    public PrintBridge(Context context) {
        this.context = context;
    }

    /**
     * Imprime bytes ESC/POS.
     * @param base64data bytes del ticket en Base64
     * llamado desde JS: window.AndroidPrint.print(btoa(String.fromCharCode(...bytes)))
     */
    @JavascriptInterface
    public String print(String base64data) {
        try {
            byte[] data = android.util.Base64.decode(base64data, android.util.Base64.DEFAULT);

            // 1. Intentar impresora interna del sistema (Sunmi, PAX, terminales)
            if (printInternal(data)) return "ok:internal";

            // 2. Intentar USB OTG
            if (printUsb(data)) return "ok:usb";

            // 3. Intentar Bluetooth Classic (SPP)
            if (printBluetooth(data)) return "ok:bluetooth";

            return "error:no_printer_found";

        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    /**
     * Guarda el nombre del dispositivo BT preferido desde JS.
     * window.AndroidPrint.setBluetoothDevice("Bluetooth Printer")
     */
    @JavascriptInterface
    public void setBluetoothDevice(String name) {
        savedBtName = name;
    }

    /**
     * Devuelve info del dispositivo para que el JS adapte su comportamiento.
     * window.AndroidPrint.getDeviceInfo()
     */
    @JavascriptInterface
    public String getDeviceInfo() {
        return "{\"model\":\"" + Build.MODEL + "\"," +
               "\"manufacturer\":\"" + Build.MANUFACTURER + "\"," +
               "\"android\":\"" + Build.VERSION.RELEASE + "\"," +
               "\"brand\":\"" + Build.BRAND + "\"}";
    }

    /**
     * Lista impresoras BT emparejadas — devuelve JSON para mostrar en selector.
     */
    @JavascriptInterface
    public String getPairedBtPrinters() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) return "[]";
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            StringBuilder sb = new StringBuilder("[");
            for (BluetoothDevice d : bonded) {
                if (sb.length() > 1) sb.append(",");
                sb.append("{\"name\":\"").append(d.getName()).append("\",")
                  .append("\"address\":\"").append(d.getAddress()).append("\"}");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    // ── MÉTODOS PRIVADOS DE IMPRESIÓN ────────────────────────────────────────

    /**
     * Impresora interna de terminales POS (Sunmi, Vizzion, etc.)
     * Usa reflexión para no requerir el SDK específico de cada fabricante.
     */
    private boolean printInternal(byte[] data) {
        // Método 1: Sunmi InnerPrinter
        try {
            Class<?> clazz = Class.forName("woyou.aidlservice.jiuiv5.IWoyouService");
            // Si la clase existe, estamos en un Sunmi — delegar a SunmiPrinter
            return SunmiPrinter.print(context, data);
        } catch (ClassNotFoundException ignored) {}

        // Método 2: impresoras genéricas — buscar device interno por nombre
        try {
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            for (UsbDevice device : devices.values()) {
                // VendorID 0x0483 = STMicroelectronics (chip común en impresoras de terminales)
                // VendorID 0x0416 = Winbond (otro chip común)
                int vid = device.getVendorId();
                if (vid == 0x0483 || vid == 0x0416 || vid == 0x6868 || vid == 0x0525) {
                    if (printToUsbDevice(usbManager, device, data)) return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Impresión por USB OTG — cualquier impresora térmica USB conectada externamente.
     */
    private boolean printUsb(byte[] data) {
        try {
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            for (UsbDevice device : devices.values()) {
                // Clase 7 = dispositivo de impresión (USB Printer Class)
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                        if (printToUsbDevice(usbManager, device, data)) return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean printToUsbDevice(UsbManager usbManager, UsbDevice device, byte[] data) {
        UsbDeviceConnection connection = null;
        try {
            if (!usbManager.hasPermission(device)) return false;
            connection = usbManager.openDevice(device);
            if (connection == null) return false;

            UsbInterface usbInterface = null;
            UsbEndpoint endpoint = null;

            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                for (int j = 0; j < intf.getEndpointCount(); j++) {
                    UsbEndpoint ep = intf.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        usbInterface = intf;
                        endpoint = ep;
                        break;
                    }
                }
                if (endpoint != null) break;
            }

            if (usbInterface == null || endpoint == null) return false;

            connection.claimInterface(usbInterface, true);

            // Enviar en chunks de 16KB
            int CHUNK = 16384;
            for (int offset = 0; offset < data.length; offset += CHUNK) {
                int len = Math.min(CHUNK, data.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(data, offset, chunk, 0, len);
                int sent = connection.bulkTransfer(endpoint, chunk, len, 3000);
                if (sent < 0) return false;
            }
            return true;

        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) connection.close();
        }
    }

    /**
     * Impresión por Bluetooth Classic (SPP).
     * Funciona con las impresoras genéricas que Web Bluetooth NO puede usar.
     */
    private boolean printBluetooth(byte[] data) {
        BluetoothSocket socket = null;
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) return false;

            BluetoothDevice printer = findPrinter(bt);
            if (printer == null) return false;

            // Conexión insegura — no requiere PIN ni emparejamiento previo en algunos casos
            try {
                Method m = printer.getClass().getMethod(
                    "createInsecureRfcommSocketToServiceRecord", UUID.class);
                socket = (BluetoothSocket) m.invoke(printer, SPP_UUID);
            } catch (Exception e) {
                socket = printer.createRfcommSocketToServiceRecord(SPP_UUID);
            }

            bt.cancelDiscovery(); // IMPORTANTE: detener discovery antes de conectar
            socket.connect();

            OutputStream out = socket.getOutputStream();

            // Enviar en chunks pequeños para no saturar el buffer BT
            int CHUNK = 100;
            for (int i = 0; i < data.length; i += CHUNK) {
                int len = Math.min(CHUNK, data.length - i);
                out.write(data, i, len);
                out.flush();
                Thread.sleep(40); // pausa entre chunks — crítica para BT barato
                if ((i / CHUNK + 1) % 10 == 0) Thread.sleep(100); // pausa extra cada 10 chunks
            }
            Thread.sleep(300); // pausa final
            return true;

        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private BluetoothDevice findPrinter(BluetoothAdapter bt) {
        Set<BluetoothDevice> bonded = bt.getBondedDevices();

        // Si hay un nombre guardado, buscar exacto
        if (savedBtName != null) {
            for (BluetoothDevice d : bonded) {
                if (savedBtName.equalsIgnoreCase(d.getName())) return d;
            }
        }

        // Buscar por nombre conocido de impresoras térmicas
        String[] knownNames = {
            "Bluetooth Printer", "BlueTooth Printer", "Printer",
            "MTP-II", "MTP-3", "RPP02", "RPP300",
        };
        String[] knownPrefixes = { "XP-", "ZJ-", "BT-", "PT-", "MT-", "DP-", "GP-" };

        for (BluetoothDevice d : bonded) {
            String name = d.getName();
            if (name == null) continue;
            for (String known : knownNames) {
                if (name.equalsIgnoreCase(known)) return d;
            }
            for (String prefix : knownPrefixes) {
                if (name.startsWith(prefix)) return d;
            }
        }

        // Último recurso: primer dispositivo emparejado
        if (!bonded.isEmpty()) return bonded.iterator().next();
        return null;
    }
}
