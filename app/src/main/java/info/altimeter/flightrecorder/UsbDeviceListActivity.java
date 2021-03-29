package info.altimeter.flightrecorder;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class UsbDeviceListActivity extends AppCompatActivity {
    UsbManager manager;
    HashMap<String, UsbDevice> deviceList;
    ArrayList<String> deviceNameList;
    PendingIntent mPermissionIntent;
    ListView list;
//    TextView text;
    ArrayAdapter adapter;
    String str = new String();
    UsbEventReceiver mUsbReceiver;
    UpdateHandler updateHandler;

    UsbDevice device = null;
    UsbInterface uif;
    UsbDeviceConnection connection;
    UsbEndpoint endpoint;
    UsbEndpoint rx = null;

    private static final String ACTION_USB_PERMISSION =
            BuildConfig.APPLICATION_ID +
            ".USB_PERMISSION";

    public class UsbEventReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            UsbEndpoint ep;

            device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                connection = manager.openDevice(device);
            }

            if (connection == null) {
                if(manager.hasPermission(device)) {
                    str = "WTF";
                } else {
                    str = "Access denied";
                }
                return;
            }

            str = connection.getSerial();
            str += " ";

            uif = device.getInterface(1);
            int n = uif.getEndpointCount();
            int i;
            for (i = 0; i < n; i += 1) {
                ep = uif.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    rx = ep;
                    break;
                }
            }

            Intent returnIntent = new Intent();
            Bundle extras = new Bundle();
            extras.putParcelable(UsbManager.EXTRA_DEVICE, device);
            returnIntent.putExtras(extras);
            UsbDeviceListActivity.this.setResult(RESULT_OK, returnIntent);
            finish();
        }
    }

    public class UpdateHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            this.sendEmptyMessageDelayed(1, 1000);
        }

    }

    public class DeviceClickListener implements AdapterView.OnItemClickListener {

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            UsbDevice device = deviceList.get(deviceNameList.get(i));
            if (device != null) {
                manager.requestPermission(device, mPermissionIntent);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_device_list);
        list = findViewById(android.R.id.list);
//        text = findViewById(R.id.str);
        manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mUsbReceiver = new UsbEventReceiver();
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

        deviceNameList = new ArrayList<>();
        registerReceiver(mUsbReceiver, filter);
        list.setOnItemClickListener(new DeviceClickListener());
        updateHandler = new UpdateHandler();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        adapter = new ArrayAdapter(this, R.layout.list_item_usb_device, R.id.name);
        list.setAdapter(adapter);
        deviceList = manager.getDeviceList();
        deviceNameList.clear();
        Iterator<String> deviceNameIterator = deviceList.keySet().iterator();
        while(deviceNameIterator.hasNext()) {
            String deviceName = deviceNameIterator.next();
            deviceNameList.add(deviceName);
            UsbDevice device = deviceList.get(deviceName);
            String itemString = String.format("%04X:%04X\n", device.getVendorId(), device.getDeviceId());
            itemString += device.getProductName();
            adapter.add(itemString);
        }
        updateHandler.sendEmptyMessageDelayed(1, 1000);
    }
}
