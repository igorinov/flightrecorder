package info.altimeter.flightrecorder;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

public class AirSpeedSensor extends Thread {
    private static final String TAG = "AirSpeedSensor";
    AirSpeedListener listener = null;
    Context context;
    UsbManager manager;
    UsbInterface ifControl = null;
    UsbInterface ifData = null;
    UsbDevice device = null;
    UsbDeviceConnection connection;
    UsbEndpoint rx = null;
    int outputType = TYPE_A;
    float pressureRange = 1.0f;

    public static final int TYPE_A = 0;
    public static final int TYPE_B = 1;

    SerialInputStream inStream = new SerialInputStream();
    byte[] temp = new byte[1024];
    boolean recording = false;

    byte[] rx_buffer = new byte[1024];

    public AirSpeedSensor(Context context) {
        this.context = context;
    }

    /**
     * Request codes as defined in USB Communication Device Class
     */
    public class Requests {
        static final int SEND_ENCAPSULATED_COMMAND = 0x00;
        static final int GET_ENCAPSULATED_RESPONSE = 0x01;
        static final int SET_COMM_FEATURE = 0x02;
        static final int GET_COMM_FEATURE = 0x03;
        static final int CLEAR_COMM_FEATURE = 0x04;
        static final int SET_LINE_CODING = 0x20;
        static final int GET_LINE_CODING = 0x21;
        static final int SET_CONTROL_LINE_STATE = 0x22;
        static final int SEND_BREAK = 0x23;
    };

    public interface AirSpeedListener {
        void onSpeedReading(int stat, float diff, float temp);
        void onDisconnect();
    }

    public class SerialInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            int length = read(temp, 0, 1);
            if (length == 1) {
                return temp[0];
            } else {
                return -1;
            }
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return read(buffer, buffer.length);
        }

        private int read(byte[] buffer, int byteCount) throws IOException {
            int result;

            if (!recording) {
                return 0;
            }
            result = connection.bulkTransfer(rx, buffer, byteCount, 1000);
            if (result < 0) {
                throw new IOException(String.format("error %d", result));
            }

            return result;
        }

        @Override
        public int read(byte[] buffer, int byteOffset, int byteCount) throws IOException {
            int result;

            if (!recording) {
                return 0;
            }

            result =  connection.bulkTransfer(rx, buffer, byteOffset, byteCount, 1000);
            if (result < 0) {
                throw new IOException(String.format("error %d", result));
            }

            return result;
        }
    }

    @Override
    public void run() {
        int baudRate = 9600;
        int requestType = UsbConstants.USB_TYPE_CLASS | 1;

        ifControl = device.getInterface(0);
        ifData = device.getInterface(1);

        manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        try {
            connection = manager.openDevice(device);
        } catch (IllegalArgumentException e) {
            connection = null;
            Log.e(TAG, "failed to open device", e);
        }

        if (connection == null) {
            if (listener != null) {
                listener.onDisconnect();
            }

            return;
        }

        connection.claimInterface(ifControl, true);
        connection.claimInterface(ifData, true);

        int n = ifData.getEndpointCount();
        int i;
        for (i = 0; i < n; i += 1) {
            UsbEndpoint ep = ifData.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                rx = ep;
                break;
            }
        }

        if (rx != null) {
            rx_buffer[0] = (byte) (baudRate & 0xFF);
            rx_buffer[1] = (byte) ((baudRate & 0xFF00) >> 8);
            rx_buffer[2] = (byte) ((baudRate & 0xFF0000) >> 16);
            rx_buffer[3] = (byte) ((baudRate & 0xFF000000) >> 24);
            rx_buffer[4] = 0;  /* 1 stop bit */
            rx_buffer[5] = 0;  /* no parity */
            rx_buffer[6] = 8;  /* data bits */
            connection.controlTransfer(requestType, Requests.SET_CONTROL_LINE_STATE, 1, 0, null, 0, 1000);
            connection.controlTransfer(requestType, Requests.SET_LINE_CODING, 0, 0, rx_buffer, 7, 1000);
        }

        BufferedReader reader;
        try {
            reader = new BufferedReader(new InputStreamReader(inStream, "UTF-8"), 128);
        } catch (UnsupportedEncodingException e) {
            reader = new BufferedReader(new InputStreamReader(inStream), 128);
        }

        while (recording) {
            if (rx == null) {
                try {
                    sleep(40);
                } catch (InterruptedException e) {
                    // OK
                }
                continue;
            }

            String s = null;
            try {
                s = reader.readLine();
            } catch (IOException e) {
                listener.onDisconnect();
                recording = false;
            }
            if (s == null) {
                continue;
            }

            if (s.length() < 9) {
                continue;
            }
            if (!s.startsWith("*")) {
                continue;
            }

            String word = s.substring(1, 5);
            int diff = Integer.parseInt(word, 16);
            int stat = ((diff & 0xC000) >> 14);
            diff &= 0x3FFF;
            word = s.substring(5, 9);
            int temp = Integer.parseInt(word, 16);
            temp >>= 5;
            float temp_c = ((temp - 511) * 100.0f) / 1024.0f;

            float diff_psi = 0;
            if (outputType == TYPE_A) {
                diff_psi = pressureRange * (diff - 8192) / 6554.0f;
            }
            if (outputType == TYPE_B) {
                diff_psi = pressureRange * (diff - 8191) / 7372.0f;
            }
            if (recording && listener != null) {
                listener.onSpeedReading(stat, diff_psi, temp_c);
            }
        }
    }

    public void setListener(AirSpeedListener listener) {
        this.listener = listener;
    }

    public void setDevice(UsbDevice device) {
        this.device = device;
    }

    public void sensorStart() {
        recording = true;
        start();
    }

    public void sensorStop() {
        int requestType = UsbConstants.USB_TYPE_CLASS | 1;

        recording = false;
        if (connection == null) {
            return;
        }

        connection.controlTransfer(requestType, Requests.SET_CONTROL_LINE_STATE, 0, 0, null, 0, 1000);
        connection.releaseInterface(ifData);
        connection.close();
    }
}
