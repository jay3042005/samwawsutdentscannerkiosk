# Logger Kiosk Mode Android App

A Kotlin Android application designed to run in kiosk mode that automatically fetches scan logs from a local PocketBase server and transfers them to nearby devices using BLE and WiFi Direct.

## Features

### Kiosk Mode Compatibility
- Runs in kiosk mode or as a headless background service
- No user interaction required
- Auto-starts on device boot
- Full-screen interface with system UI hidden

### PocketBase Integration
- Connects to local PocketBase server at `http://127.0.0.1:8090`
- Fetches all documents from the `scan_logs` collection
- Extracts only required fields: `student_id`, `student_name`, `entry_exit_status`, `timestamp`
- Minimizes JSON format for efficient transfer

### File Preparation
- Saves minimized JSON to `scan_logs.json`
- Optionally creates gzipped version (`scan_logs.gz`)
- Strips unnecessary metadata

### Auto File Transfer
- Automatically detects nearby Android devices/tablets
- Uses BLE for initial handshake ("ready to receive scan logs")
- Switches to WiFi Direct or Classic Bluetooth (SPP) for file transfer
- Works without manual approval (auto-accept)

### Auto Cleanup
- Stops hotspot/WiFi Direct after file transfer
- Closes sockets and connections
- Restarts workflow automatically

### Optional Features
- Base64 encoding for BLE chunk transfer
- Timestamps and log viewer inside app
- File transfer retry (up to 3 times)
- Comprehensive logging

## Technical Stack

### Networking
- **Retrofit2** for PocketBase REST API calls
- **OkHttp3** for HTTP client
- **GSON** for JSON parsing

### Bluetooth & WiFi
- **Android BLE APIs** for device scanning and connection
- **WifiP2pManager** for WiFi Direct file transfer
- **BluetoothSocket** for classic Bluetooth SPP

### Background Processing
- **WorkManager** for background tasks
- **ForegroundService** for persistent operation
- **Coroutines** for asynchronous operations

## Project Structure

```
app/src/main/java/com/example/logger/
├── data/
│   ├── api/
│   │   └── PocketBaseApi.kt          # Retrofit API interface
│   ├── model/
│   │   └── ScanLog.kt                # Data models
│   └── repository/
│       └── ScanLogRepository.kt      # Data management
├── bluetooth/
│   └── BleManager.kt                 # BLE operations
├── wifi/
│   └── WifiDirectManager.kt          # WiFi Direct operations
├── service/
│   └── LoggerService.kt              # Main background service
├── receiver/
│   └── BootReceiver.kt               # Auto-start on boot
├── MainActivity.kt                   # Main UI (kiosk mode)
├── KioskActivity.kt                  # Minimal kiosk interface
└── LoggerApplication.kt              # Application class
```

## Setup Instructions

### 1. Prerequisites
- Android Studio
- Android device with API level 29+ (Android 10+)
- PocketBase server running on `127.0.0.1:8090`
- `scan_logs` collection in PocketBase

### 2. Build Configuration
The app uses the following dependencies:
- Retrofit2 for API calls
- GSON for JSON parsing
- WorkManager for background tasks
- Coroutines for async operations

### 3. Permissions
The app requires these permissions:
- Bluetooth (connect, scan, advertise)
- WiFi (state, direct)
- Location (for device discovery)
- Storage (read/write)
- Foreground service

### 4. PocketBase Setup
Ensure your PocketBase server has a `scan_logs` collection with these fields:
- `student_id` (text)
- `student_name` (text)
- `entry_exit_status` (text)
- `timestamp` (date)

## Usage

### Kiosk Mode
1. Install the app on the target device
2. Grant all required permissions
3. The app will automatically start the LoggerService
4. The service will continuously:
   - Fetch scan logs from PocketBase
   - Advertise BLE signals
   - Discover nearby devices
   - Transfer files automatically

### Manual Mode
1. Open the app
2. Tap "Start Service" to begin operation
3. Use "Refresh Logs" to view current scan logs
4. Tap "Stop Service" to halt operation

### Log Viewer
The app includes a built-in log viewer that displays:
- Student ID
- Student Name
- Entry/Exit Status
- Timestamp

## Configuration

### PocketBase URL
Edit `LoggerService.kt` line 47 to change the PocketBase server URL:
```kotlin
.baseUrl("http://127.0.0.1:8090/")
```

### BLE Service UUID
Edit `BleManager.kt` to customize BLE service UUIDs:
```kotlin
private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
```

### Retry Settings
Edit `LoggerService.kt` to modify retry behavior:
```kotlin
private const val MAX_RETRY_ATTEMPTS = 3
```

## Troubleshooting

### Common Issues

1. **Permission Denied**
   - Ensure all permissions are granted in app settings
   - Check device location is enabled

2. **PocketBase Connection Failed**
   - Verify PocketBase server is running
   - Check network connectivity
   - Ensure correct URL in configuration

3. **BLE/WiFi Direct Not Working**
   - Check device supports BLE and WiFi Direct
   - Ensure Bluetooth and WiFi are enabled
   - Verify location permissions

4. **File Transfer Fails**
   - Check target device is compatible
   - Ensure both devices support the same transfer method
   - Verify file permissions

### Debug Mode
Enable debug logging by checking Logcat with tag filters:
- `LoggerService`
- `BleManager`
- `WifiDirectManager`
- `ScanLogRepository`

## Security Considerations

- The app uses cleartext HTTP for local PocketBase connection
- BLE advertising is discoverable
- File transfers are unencrypted
- Consider implementing encryption for production use

## License

This project is provided as-is for educational and development purposes.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## Support

For issues and questions, please check the troubleshooting section or create an issue in the repository. 