# Flight Recorder

This application records the following data:
- Airspeed, if an Arduino-based device running [the Airspeed project](https://github.com/igorinov/airspeed) is connected;
- Barometric pressure, if the device has an internal pressure sensor;
- GNSS latitude/longitude, altitude, ground speed
- GNSS horizontal, vertical, and ground speed accuracy

Log files are written to internal shared storage:

    Android/data/info.altimeter.flightrecorder/files

