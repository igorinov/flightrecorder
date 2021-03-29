package info.altimeter.flightrecorder;

import android.app.Notification;
import android.app.NotificationChannel;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Locale;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";
    private static final String CHANNEL_ID = "Channel 9";
    LocationManager locationManager;
    RecorderLocationListener locationListener;
    PressureListener pressureListener;
    AirSpeedSensor speedSensor;
    UsbDevice device;
    String logFileName = "default";
    boolean locationEnabled = false;
    boolean pressureEnabled = false;
    boolean airspeedEnabled = false;
    File locationLog;
    File pressureLog;
    File airspeedLog;
    FileOutputStream locationStream;
    FileOutputStream pressureStream;
    FileOutputStream airspeedStream;
    OutputStreamWriter locationWriter;
    OutputStreamWriter pressureWriter;
    OutputStreamWriter airspeedWriter;
    HandlerThread pressureThread;
    Handler pressureHandler;
    boolean recording = false;
    private final IBinder mBinder = new TrackRecordingServiceBinder();
    SensorManager sensorManager;
    Sensor sensor;
    NotificationManager notificationManager;
    NotificationCompat.Builder mBuilder;
    int notifyID = 1;
    boolean foregroundState = false;
    long number = 0;
    long startTime = 0;

    public static final int FLAGS_LOCATION = (1 << 0);
    public static final int FLAGS_PRESSURE = (1 << 1);
    public static final int FLAGS_AIRSPEED = (1 << 2);

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            AudioAttributes.Builder aab = new AudioAttributes.Builder();
            aab.setUsage(AudioAttributes.USAGE_NOTIFICATION);
            aab.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
            Uri ding = Uri.parse("android.resource://"
                    + BuildConfig.APPLICATION_ID + "/" + R.raw.ding);
            channel.setSound(ding, aab.build());
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (recording) {
            Log.e(TAG, "service killed while recording");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flag, int startId) {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent notifyPendingIntent = PendingIntent.getActivity(
                this, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT
        );

        mBuilder = new NotificationCompat.Builder(this, CHANNEL_ID);
        mBuilder.setContentTitle("Recording");
        mBuilder.setContentText(logFileName);
        mBuilder.setSmallIcon(R.drawable.ic_box_24dp);
        mBuilder.setContentIntent(notifyPendingIntent);
        mBuilder.setAutoCancel(false);
        mBuilder.setCategory(Notification.CATEGORY_SERVICE);
        mBuilder.setOngoing(true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Uri ding = Uri.parse("android.resource://"
                    + BuildConfig.APPLICATION_ID + "/" + R.raw.ding);
            mBuilder.setSound(ding);
        }

        try {
            startForeground(notifyID, mBuilder.build());
            foregroundState = true;
        } catch (IllegalStateException e) {
            notificationManager.notify(notifyID, mBuilder.build());
            foregroundState = false;
        }

        return Service.START_STICKY;
    }

    public class TrackRecordingServiceBinder extends Binder {
        RecordingService getService() {
            return RecordingService.this;
        }
    }

    private String logTime() {
        long now = SystemClock.elapsedRealtimeNanos();
        return logTime(now);
    }

    private String logTime(long eventTime) {
        long ns = eventTime - startTime;
        long ms = ns / 1000000;
        long s = ms / 1000;
        ms %= 1000;
        long m = s / 60;
        s %= 60;
        long h = m / 60;
        m %= 60;
        return String.format(Locale.US, "%4d:%02d:%02d.%03d", h, m, s, ms);
    }

    class RecorderAirspeedListener implements AirSpeedSensor.AirSpeedListener {
        @Override
        public void onSpeedReading(int stat, float diff, float temp_c) {
            if (airspeedWriter == null) {
                return;
            }
            String str = logTime();
            str += String.format(Locale.US, "%d %+8.4f %+5.1f\n", stat, diff, temp_c);
            try {
                airspeedWriter.write(str);
            } catch (IOException e) {
                Log.e(TAG, "error writing airspeed log", e);
            }
        }

        @Override
        public void onDisconnect() {
            Log.e(TAG, "airspeed sensor disconnected");
            if (airspeedWriter == null) {
                return;
            }
            try {
                airspeedWriter.flush();
            } catch (IOException e) {
                Log.e(TAG, "error flushing airspeed log", e);
            }
        }
    }

    class RecorderLocationListener implements LocationListener {

        @Override
        public void onLocationChanged(Location location) {
            long t;
            String str;

            t = location.getElapsedRealtimeNanos();
            str = logTime(t);

            str += " ";
            str += String.format(Locale.US, "%+11.6f", location.getLongitude());
            str += " ";
            str += String.format(Locale.US, "%+11.6f", location.getLatitude());
            str += " ";
            str += String.format(Locale.US, "%11.6f", location.getAccuracy());

            str += " ";
            str += String.format(Locale.US, "%+7.1f", location.getAltitude());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                str += " ";
                float std = location.getVerticalAccuracyMeters();
                str += String.format(Locale.US, "%7.3f", std);
            }

            /*
             * Log ground speed, with uncertainty
             */
            str += " ";
            str += String.format(Locale.US, "%7.3f", location.getSpeed());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                str += " ";
                float std = location.getSpeedAccuracyMetersPerSecond();
                str += String.format(Locale.US, "%7.3f", std);
            }
            str += "\n";

            if (locationStream != null) {
                try {
                    locationStream.write(str.getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            number += 1;
        }

        @Override
        public void onProviderDisabled(String provider) {
            // OK
        }

        @Override
        public void onProviderEnabled(String provider) {
            // OK
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // OK
        }
    }

    private class PressureListener implements SensorEventListener {

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Whatever
        }

        public void onSensorChanged(SensorEvent event) {
            String str;
            float p;

            str = logTime(event.timestamp);
            p = event.values[0];
            str += String.format(Locale.US, " %7.3f\n", p);
            if (pressureStream != null) {
                try {
                    pressureStream.write(str.getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    void notifyHost() {
        ArrayList<String> nameList = new ArrayList<>();
        if (locationLog != null) {
            nameList.add(locationLog.toString());
        }
        if (pressureLog != null) {
            nameList.add(pressureLog.toString());
        }
        if (airspeedLog != null) {
            nameList.add(airspeedLog.toString());
        }
        String[] names = new String[nameList.size()];
        nameList.toArray(names);
        MediaScannerConnection.scanFile(this, names, null, null);
    }

    public void setAirspeedDevice(UsbDevice device) {
        this.device = device;
    }

    public void setLogFilename(String filename) {
        this.logFileName = filename;
    }

    public UsbDevice getAirspeedDevice() {
        return this.device;
    }

    public int recordStart(int flags) {
        airspeedEnabled = ((flags & FLAGS_AIRSPEED) != 0);
        pressureEnabled = ((flags & FLAGS_PRESSURE) != 0);
        locationEnabled = ((flags & FLAGS_LOCATION) != 0);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        locationListener = new RecorderLocationListener();
        pressureListener = new PressureListener();

        startTime = SystemClock.elapsedRealtimeNanos();

        File appExtDir = getApplicationContext().getExternalFilesDir(null);
        File dir = new File(appExtDir.getAbsolutePath());
        boolean status = dir.mkdirs();

        if (ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationEnabled = false;
        }

        if (locationEnabled) {
            locationLog = new File(dir, logFileName + ".location.log");
            try {
                locationLog.createNewFile();
                locationStream = new FileOutputStream(locationLog);
                locationWriter = new OutputStreamWriter(locationStream);

                locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        40, 1, locationListener);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "location log file not found", e);
                locationEnabled = false;
            } catch (IOException e) {
                Log.e(TAG, "failed to create location log file", e);
                locationEnabled = false;
            }
        }

        if (pressureEnabled) {
            pressureLog = new File(dir, logFileName + ".pressure.log");
            try {
                pressureLog.createNewFile();
                pressureStream = new FileOutputStream(pressureLog);
                pressureWriter = new OutputStreamWriter(pressureStream);

                pressureThread = new HandlerThread("pressure", Process.THREAD_PRIORITY_MORE_FAVORABLE);
                pressureThread.start();
                pressureHandler = new Handler(pressureThread.getLooper());
                sensorManager.registerListener(pressureListener, sensor, 40000, pressureHandler);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "pressure log file not found", e);
                pressureEnabled = false;
            } catch (IOException e) {
                Log.e(TAG, "failed to create pressure log file", e);
                pressureEnabled = false;
            }
        }

        if (airspeedEnabled) {
            airspeedLog = new File(dir, logFileName + ".airspeed.log");
            try {
                airspeedLog.createNewFile();
                airspeedStream = new FileOutputStream(airspeedLog);
                airspeedWriter = new OutputStreamWriter(airspeedStream);

                speedSensor = new AirSpeedSensor(this);
                speedSensor.setListener(new RecorderAirspeedListener());
                speedSensor.setDevice(device);
                speedSensor.sensorStart();
            } catch (FileNotFoundException e) {
                Log.e(TAG, "airspeed log file not found", e);
                airspeedEnabled = false;
            } catch (IOException e) {
                Log.e(TAG, "failed to create airspeed log file", e);
                airspeedEnabled = false;
            }
        }

        recording = true;

        return 0;
    }

    public void recordStop() {
        recording = false;

        if (airspeedEnabled) {
            speedSensor.sensorStop();
            if (airspeedWriter != null) {
                try {
                    airspeedWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                airspeedWriter = null;
            }
            airspeedStream = null;
            airspeedLog = null;
        }

        if (pressureEnabled) {
            sensorManager.unregisterListener(pressureListener);
            if (pressureWriter != null) {
                try {
                    pressureWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "error closing pressure log file", e);
                }
                pressureWriter = null;
            }
            pressureStream = null;
            pressureLog = null;
            pressureThread.quitSafely();
        }

        if (locationEnabled) {
            locationManager.removeUpdates(locationListener);
            if (locationWriter != null) {
                try {
                    locationWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                locationWriter = null;
            }
            locationStream = null;
            locationLog = null;
        }

        if (foregroundState) {
            stopForeground(true);
        } else {
            notificationManager.cancel(notifyID);
        }
        notifyHost();
    }

    public boolean isRecording() {
        return recording;
    }

    public String getFileName() {
        return logFileName;
    }

    public boolean isLocationRecorded() {
        return locationEnabled;
    }

    public boolean isPressureRecorded() {
        return pressureEnabled;
    }

    public boolean isAirspeedRecorded() {
        return airspeedEnabled;
    }
}
