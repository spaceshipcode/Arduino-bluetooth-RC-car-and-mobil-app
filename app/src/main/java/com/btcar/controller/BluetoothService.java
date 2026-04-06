package com.btcar.controller;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Bluetooth Classic SPP bağlantı yöneticisi.
 * HC-05/HC-06 modülüne bağlanır ve veri gönderir.
 */
public class BluetoothService {

    private static final String TAG = "BluetoothService";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface ConnectionListener {
        void onConnected(String deviceName);
        void onDisconnected();
        void onConnectionFailed(String error);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ConnectionListener listener;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private ConnectThread connectThread;
    private volatile boolean connected = false;

    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        // Önceki bağlantıyı kapat
        disconnect();

        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    public void disconnect() {
        connected = false;
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        closeSocket();
        if (listener != null) {
            mainHandler.post(() -> listener.onDisconnected());
        }
    }

    /**
     * Arduino'ya string mesaj gönderir.
     * Thread-safe: herhangi bir thread'den çağrılabilir.
     */
    public synchronized void send(String message) {
        if (!connected || outputStream == null) return;
        try {
            outputStream.write(message.getBytes());
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Send failed", e);
            connected = false;
            mainHandler.post(() -> {
                closeSocket();
                if (listener != null) listener.onDisconnected();
            });
        }
    }

    private void closeSocket() {
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        outputStream = null;
        socket = null;
    }

    @SuppressLint("MissingPermission")
    private class ConnectThread extends Thread {
        private final BluetoothDevice device;
        private BluetoothSocket tmpSocket;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
            setName("BT-Connect-" + device.getName());
        }

        @Override
        public void run() {
            try {
                tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException e) {
                notifyFailed("Soket oluşturulamadı");
                return;
            }

            // Bluetooth discovery'yi durdur
            try {
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
            } catch (SecurityException ignored) {}

            try {
                tmpSocket.connect();
            } catch (IOException connectException) {
                // Fallback: reflection ile port 1 denemesi
                try {
                    tmpSocket.close();
                    tmpSocket = (BluetoothSocket) device.getClass()
                            .getMethod("createRfcommSocket", int.class)
                            .invoke(device, 1);
                    if (tmpSocket != null) {
                        tmpSocket.connect();
                    } else {
                        notifyFailed("Bağlantı başarısız");
                        return;
                    }
                } catch (Exception e2) {
                    try { tmpSocket.close(); } catch (IOException ignored) {}
                    notifyFailed("Bağlantı başarısız: " + e2.getMessage());
                    return;
                }
            }

            // Bağlantı başarılı
            try {
                socket = tmpSocket;
                outputStream = socket.getOutputStream();
                connected = true;
                String name = device.getName() != null ? device.getName() : device.getAddress();
                mainHandler.post(() -> {
                    if (listener != null) listener.onConnected(name);
                });
            } catch (IOException e) {
                notifyFailed("OutputStream alınamadı");
            }
        }

        void cancel() {
            try {
                if (tmpSocket != null) tmpSocket.close();
            } catch (IOException ignored) {}
        }

        private void notifyFailed(String msg) {
            mainHandler.post(() -> {
                if (listener != null) listener.onConnectionFailed(msg);
            });
        }
    }
}
