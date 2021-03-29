package info.altimeter.flightrecorder;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.TimeZone;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

public class MainActivity extends AppCompatActivity {
    Button buttonStart;
    Button buttonStop;
    Button buttonSelectDevice;
    CheckBox checkLocation;
    CheckBox checkPressure;
    CheckBox checkAirspeed;
    EditText editFileName;
    TextView viewDeviceInfo;

    UsbDevice deviceAirSpeed;
    RecordingService recordService = null;
    TrackRecordingServiceConnection connection = null;
    GregorianCalendar time;
    boolean responseReceived = false;
    boolean hasBarometer = false;

    static final int REQUEST_AIRSPEED_DEVICE_SELECT = 1000;

    class TrackRecordingServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RecordingService.TrackRecordingServiceBinder binder;

            binder = (RecordingService.TrackRecordingServiceBinder) service;
            recordService = binder.getService();

            boolean recording = recordService.isRecording();
            if (buttonStart != null) {
                buttonStart.setEnabled(!recording);
            }
            if (buttonStop != null) {
                buttonStop.setEnabled(recording);
            }

            if (recording) {
                deviceAirSpeed  = recordService.getAirspeedDevice();
                updateDeviceInfo();
                checkLocation.setChecked(recordService.isLocationRecorded());
                checkLocation.setEnabled(false);
                checkPressure.setChecked(recordService.isPressureRecorded());
                checkPressure.setEnabled(false);
                checkAirspeed.setChecked(recordService.isAirspeedRecorded());
                checkAirspeed.setEnabled(false);
                String filename = recordService.getFileName();
                if (filename != null) {
                    editFileName.setText(filename);
                }
                editFileName.setEnabled(false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordService = null;
            buttonStart.setEnabled(false);
            buttonStop.setEnabled(false);
        }

    };

    class StartButtonListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            recordService.setLogFilename(editFileName.getText().toString());
            recordService.setAirspeedDevice(deviceAirSpeed);

            Intent intent = new Intent(MainActivity.this, RecordingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            if (recordService != null) {
                int flags = 0;
                if (checkAirspeed.isChecked()) {
                    flags |= RecordingService.FLAGS_AIRSPEED;
                }
                if (checkPressure.isChecked()) {
                    flags |= RecordingService.FLAGS_PRESSURE;
                }
                if (checkLocation.isChecked()) {
                    flags |= RecordingService.FLAGS_LOCATION;
                }
                recordService.recordStart(flags);
                buttonStart.setEnabled(false);
                buttonStop.setEnabled(true);
                checkLocation.setEnabled(false);
                checkPressure.setEnabled(false);
                checkAirspeed.setEnabled(false);
                editFileName.setEnabled(false);
            }
        }
    }

    class StopButtonListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            Intent intent = new Intent(MainActivity.this, RecordingService.class);
            if (recordService != null) {
                recordService.recordStop();
            }
            checkLocation.setEnabled(true);
            checkPressure.setEnabled(hasBarometer);
            if (airspeedSensorConnected()) {
                checkAirspeed.setEnabled(true);
            } else {
                checkAirspeed.setChecked(false);
                checkAirspeed.setEnabled(false);
            }
            updateDeviceInfo();

            stopService(intent);

            buttonStop.setEnabled(false);
            buttonStart.setEnabled(true);
            editFileName.setEnabled(true);
            newFileName();
        }
    }

    class SelectDeviceButtonListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            Intent intent = new Intent(MainActivity.this, UsbDeviceListActivity.class);
            startActivityForResult(intent, 0);
        }
    }

    class LocationCheckListener implements CompoundButton.OnCheckedChangeListener {

        @Override
        public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
            if (b) {
                ArrayList<String> missingPermissions = new ArrayList<>();
                Context context = MainActivity.this;

                int status = ContextCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION);
                if (status != PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(ACCESS_FINE_LOCATION);
                }
                if (!missingPermissions.isEmpty()) {
                    String[] permissions = new String[missingPermissions.size()];
                    missingPermissions.toArray(permissions);
                    ActivityCompat.requestPermissions(MainActivity.this, permissions, 1);
                }
            }
        }
    }

    private void newFileName() {
        if (editFileName != null) {
            time = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
            editFileName.setText(String.format("%d%02d%02d%02d%02d%02d",
                    time.get(Calendar.YEAR),
                    time.get(Calendar.MONTH) + 1 - Calendar.JANUARY,
                    time.get(Calendar.DAY_OF_MONTH),
                    time.get(Calendar.HOUR_OF_DAY),
                    time.get(Calendar.MINUTE),
                    time.get(Calendar.SECOND)));
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewDeviceInfo = findViewById(R.id.device_info);
        editFileName = findViewById(R.id.filename);
        checkLocation = findViewById(R.id.log_position);
        checkPressure = findViewById(R.id.log_pressure);
        checkAirspeed = findViewById(R.id.log_airspeed);
        buttonStart = findViewById(R.id.start);
        buttonStop = findViewById(R.id.stop);
        buttonSelectDevice = findViewById(R.id.select_device);

        newFileName();

        if (checkPressure != null) {
            SensorManager manager = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            if (sensor == null) {
                checkPressure.setChecked(false);
                checkPressure.setEnabled(false);
            } else {
                hasBarometer = true;
                checkPressure.setChecked(true);
            }
        }

        if (checkLocation != null) {
            checkLocation.setOnCheckedChangeListener(new LocationCheckListener());
        }

        if (checkAirspeed != null) {
            checkAirspeed.setChecked(false);
            checkAirspeed.setEnabled(false);
        }

        if (buttonStart != null) {
            buttonStart.setEnabled(false);
        }

        if (buttonStop != null) {
            buttonStop.setEnabled(false);
        }

        if (buttonStart != null) {
            buttonStart.setOnClickListener(new StartButtonListener());
        }

        if (buttonStop != null) {
            buttonStop.setEnabled(false);
            buttonStop.setOnClickListener(new StopButtonListener());
        }

        if (buttonSelectDevice != null) {
            buttonSelectDevice.setOnClickListener(new SelectDeviceButtonListener());
        }

        connection = new TrackRecordingServiceConnection();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (recordService == null) {
            Intent intent = new Intent(MainActivity.this, RecordingService.class);
            bindService(intent, connection, BIND_AUTO_CREATE);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
//        unbindService(connection);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, @NonNull int[] grantResults) {
        int i;

        for (i = 0; i < permissions.length; i += 1) {
            if (permissions[i].equals(ACCESS_FINE_LOCATION)) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    checkLocation.setChecked(false);
                }
            }

            if (permissions[i].equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    checkLocation.setChecked(false);
                }
            }
        }
        responseReceived = true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data == null) {
            deviceAirSpeed = null;
        } else {
            deviceAirSpeed = data.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        updateDeviceInfo();

        if (deviceAirSpeed == null) {
            checkAirspeed.setEnabled(false);
            checkAirspeed.setChecked(false);
        } else {
            checkAirspeed.setEnabled(true);
            checkAirspeed.setChecked(true);
        }
    }

    private void updateDeviceInfo() {
        if (viewDeviceInfo == null) {
            return;
        }

        String stringInfo = new String();

        if (airspeedSensorConnected()) {
            stringInfo += String.format("%04X", deviceAirSpeed.getVendorId());
            stringInfo += ":";
            stringInfo += String.format("%04X", deviceAirSpeed.getDeviceId());
            stringInfo += "\n";
            stringInfo += deviceAirSpeed.getProductName();
        }

        viewDeviceInfo.setText(stringInfo);
    }

    private boolean airspeedSensorConnected() {
        if (deviceAirSpeed == null) {
            return false;
        }

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();

        return deviceList.containsValue(deviceAirSpeed);
    }
}
