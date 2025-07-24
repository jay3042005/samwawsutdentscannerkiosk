$files = @(
    "app\src\test\java\com\example\logger\ExampleUnitTest.kt",
    "app\src\main\java\com\example\logger\receiver\BootReceiver.kt",
    "app\src\main\java\com\example\logger\wifi\WifiDirectManager.kt",
    "app\src\main\java\com\example\logger\utils\QRCodeScanner.kt",
    "app\src\main\java\com\example\logger\util\FileTransferUtil.kt",
    "app\src\main\java\com\example\logger\service\LoggerService.kt",
    "app\src\main\java\com\example\logger\data\repository\ScanLogRepository.kt",
    "app\src\main\java\com\example\logger\ReceivedLogsDialog.kt",
    "app\src\main\java\com\example\logger\data\model\ScanLog.kt",
    "app\src\main\java\com\example\logger\data\api\PocketBaseApi.kt",
    "app\src\main\java\com\example\logger\MainActivity.kt",
    "app\src\main\java\com\example\logger\LoggerApplication.kt",
    "app\src\main\java\com\example\logger\KioskActivity.kt",
    "app\src\androidTest\java\com\example\logger\ExampleInstrumentedTest.kt",
    "app\src\main\java\com\example\logger\bluetooth\BleManager.kt",
    "app\src\main\java\com\example\logger\bluetooth\BluetoothServer.kt",
    "app\src\main\java\com\example\logger\bluetooth\BluetoothClient.kt"
)

$basePath = "c:\Users\Admin\Documents\pc backup new\Student Sscanner\composeApp\src\androidMain\LOgger"

foreach ($file in $files) {
    $fullPath = Join-Path $basePath $file
    if (Test-Path $fullPath) {
        $content = Get-Content $fullPath -Raw
        $content = $content -replace 'package com\.arjay\.logger', 'package com.arjay.logger'
        $content = $content -replace 'import com\.arjay\.logger', 'import com.arjay.logger'
        Set-Content -Path $fullPath -Value $content -NoNewline
        Write-Host "Updated: $file"
    } else {
        Write-Host "File not found: $file"
    }
}

Write-Host "Package name update complete!"
