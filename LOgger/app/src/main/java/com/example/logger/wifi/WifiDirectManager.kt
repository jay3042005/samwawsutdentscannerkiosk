package com.arjay.logger.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiDirectManager"
        private const val PORT = 8888
    }
    
    private val wifiP2pManager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = wifiP2pManager.initialize(context, context.mainLooper, null)
    
    private var onDeviceFound: ((WifiP2pDevice) -> Unit)? = null
    private var onConnectionChanged: ((WifiP2pInfo) -> Unit)? = null
    private var onFileTransferComplete: (() -> Unit)? = null
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    
    fun startDiscovery(onDeviceFound: (WifiP2pDevice) -> Unit) {
        this.onDeviceFound = onDeviceFound
        
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction("android.net.wifi.p2p.CONNECTION_STATE_CHANGE")
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        context.registerReceiver(wifiP2pReceiver, intentFilter)
        
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery started successfully")
            }
            
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Discovery failed: $reasonCode")
            }
        })
    }
    
    fun stopDiscovery() {
        try {
            context.unregisterReceiver(wifiP2pReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        
        wifiP2pManager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery stopped successfully")
            }
            
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Failed to stop discovery: $reasonCode")
            }
        })
    }
    
    fun connectToDevice(device: WifiP2pDevice, onConnectionChanged: (WifiP2pInfo) -> Unit) {
        this.onConnectionChanged = onConnectionChanged
        
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection request sent successfully")
            }
            
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Connection request failed: $reasonCode")
            }
        })
    }
    
    fun disconnect() {
        wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Disconnected successfully")
            }
            
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Failed to disconnect: $reasonCode")
            }
        })
    }
    
    fun sendFile(file: File, onComplete: () -> Unit) {
        this.onFileTransferComplete = onComplete
        
        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Server socket created on port $PORT")
                
                val socket = serverSocket?.accept()
                socket?.let { clientSocket = it }
                
                val outputStream: OutputStream? = socket?.getOutputStream()
                val fileInputStream = FileInputStream(file)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream?.write(buffer, 0, bytesRead)
                }
                
                outputStream?.flush()
                fileInputStream.close()
                socket?.close()
                serverSocket?.close()
                
                Log.d(TAG, "File sent successfully")
                onComplete()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending file: ${e.message}")
            }
        }.start()
    }
    
    fun receiveFile(file: File, onComplete: () -> Unit) {
        Thread {
            try {
                clientSocket = Socket()
                clientSocket?.connect(InetSocketAddress("192.168.49.1", PORT))
                
                val inputStream = clientSocket?.getInputStream()
                val fileOutputStream = file.outputStream()
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (inputStream?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead)
                }
                
                fileOutputStream.close()
                clientSocket?.close()
                
                Log.d(TAG, "File received successfully")
                onComplete()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving file: ${e.message}")
            }
        }.start()
    }
    
    fun requestPeers() {
        wifiP2pManager.requestPeers(channel, object : WifiP2pManager.PeerListListener {
            override fun onPeersAvailable(peers: WifiP2pDeviceList) {
                peers.deviceList.forEach { device ->
                    onDeviceFound?.invoke(device)
                }
            }
        })
    }
    
    private val wifiP2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.d(TAG, "WiFi P2P state changed: $state")
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager.requestPeers(channel, object : WifiP2pManager.PeerListListener {
                        override fun onPeersAvailable(peers: WifiP2pDeviceList) {
                            peers.deviceList.forEach { device ->
                                onDeviceFound?.invoke(device)
                            }
                        }
                    })
                }
                
                "android.net.wifi.p2p.CONNECTION_STATE_CHANGE" -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    @Suppress("DEPRECATION")
                    val wifiP2pInfo = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    
                    @Suppress("DEPRECATION")
                    if (networkInfo?.isConnected == true && wifiP2pInfo != null) {
                        onConnectionChanged?.invoke(wifiP2pInfo)
                    }
                }
                
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Log.d(TAG, "This device changed: ${device?.deviceName}")
                }
            }
        }
    }
} 