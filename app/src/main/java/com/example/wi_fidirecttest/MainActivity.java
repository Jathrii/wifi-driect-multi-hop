package com.example.wi_fidirecttest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    final String TAG = this.getClass().toString();
    final String DEVICE_A_MAC = "ee:d0:9f:1e:4c:e2";
    final String DEVICE_B_MAC = "0a:78:08:a9:3e:bc";
    //    final String DEVICE_B_MAC = "ae:57:75:5b:51:10";
    final String DEVICE_C_MAC = "ae:5f:3e:f8:7b:fc";

    final int TTL = 8;

    boolean waiting;
    boolean sending;
    boolean forwardState;

    byte[] currentMessage;

    Button btnOnOff, btnSend, btnDisconnect;
    Spinner spinDestDevice;
    TextView read_msg_box, connectionStatus;
    EditText writeMsg;
    Switch forwardSwitch;

    WifiManager wifiManager;
    WifiP2pManager mManager;
    WifiP2pManager.Channel mChannel;

    BroadcastReceiver mReceiver;
    IntentFilter mIntentFilter;

    List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    String[] deviceNameArray;
    WifiP2pDevice[] deviceArray;
    public WifiP2pDevice currentDevice;

    static final int MESSAGE_READ = 1;

    ServerClass serverClass;
    ClientClass clientClass;
    SendReceive sendReceive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initialWork();
        exqListener();

        discover();
    }

    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_READ:
                    byte[] readBuff = (byte[]) msg.obj;
                    String tempMsg = new String(readBuff);
                    read_msg_box.setText(tempMsg);
                    break;
            }
            return true;
        }
    });

    private void exqListener() {
        btnOnOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (wifiManager.isWifiEnabled()) {
                    wifiManager.setWifiEnabled(false);
                    btnOnOff.setText("ON");
                } else {
                    wifiManager.setWifiEnabled(true);
                    btnOnOff.setText("OFF");
                }
            }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String destAddress = "";

                switch (spinDestDevice.getSelectedItem().toString()) {
                    case "DeviceA":
                        destAddress = DEVICE_A_MAC;
                        break;
                    case "DeviceB":
                        destAddress = DEVICE_B_MAC;
                        break;
                    case "DeviceC":
                        destAddress = DEVICE_C_MAC;
                        break;
                }

                String mssg = writeMsg.getText().toString();
                currentMessage = (TTL + "," + destAddress + "," + mssg.length() + "," + mssg).getBytes();
                sending = true;

                establishConnection(destAddress);
            }
        });

        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnect();
            }
        });

        forwardSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                forwardState = isChecked;
            }
        });
    }

    public void discover() {
        Timer timer = new Timer();
        TimerTask discoveryTimer = new TimerTask() {
            @Override
            public void run() {
                if (waiting)
                    return;

                waiting = true;

                Log.d(TAG, "run: Timer Before");
                mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        connectionStatus.setText("Discovery Started");
                    }

                    @Override
                    public void onFailure(int i) {
                        waiting = false;
                        connectionStatus.setText("Discovery Starting Failed " + i);
                    }
                });

                Log.d(TAG, "run: Timer After");
            }
        };
        timer.schedule(discoveryTimer, 1, 5000);
    }

    private void establishConnection(final String destAddress) {
        Log.d(TAG, "run: ESTABLISHING");
        WifiP2pDevice targetDevice = null;

        if (!forwardState) {
            for (WifiP2pDevice device : deviceArray) {
                if (device.deviceAddress.equals(destAddress)) {
                    Log.d(TAG, "run: FOUND DEVICE: " + destAddress);
                    targetDevice = device;
                    break;
                }
            }
        }

        if (deviceArray.length > 0 && targetDevice == null) {
            //targetDevice = deviceArray[0];
            String[] networkDevicesArr = {DEVICE_A_MAC, DEVICE_B_MAC, DEVICE_C_MAC};
            ArrayList<String> networkDevices = new ArrayList<>(Arrays.asList(networkDevicesArr));

            networkDevices.remove(currentDevice.deviceAddress);
            networkDevices.remove(destAddress);

            for (WifiP2pDevice device : deviceArray) {
                if (device.deviceAddress.equals(networkDevices.get(0))) {
                    targetDevice = device;
                    break;
                }
            }
        }

        if (targetDevice != null) {
            final WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = targetDevice.deviceAddress;

            Log.d(TAG, "establishConnection: before connecting");

            if (serverClass != null) {
                Log.d(TAG, "establishConnection: serversocket in serverclass: " + serverClass.serverSocket);
                Log.d(TAG, "establishConnection: socket in serverclass: " + serverClass.socket);
            }

            if (clientClass != null)
                Log.d(TAG, "establishConnection: socket in clientclass: " + clientClass.socket);
            final Timer delay = new Timer();
            TimerTask delayTask = new TimerTask() {
                @Override
                public void run() {
                    mManager.connect(mChannel, config, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Toast.makeText(getApplicationContext(), "Connected", Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "onSuccess: SUCCESS SO WTF");
                            delay.cancel();
                            delay.purge();
                        }

                        @Override
                        public void onFailure(int i) {
                            Toast.makeText(getApplicationContext(), "Failed to connect: " + i, Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "Failed to connect: " + i);
                        }
                    });
                }
            };
            delay.schedule(delayTask, 1000, 3000);
        } else
            Log.d(TAG, "run: No Devices Available");
    }

    private void disconnect() {
        mManager.removeGroup(mChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                connectionStatus.setText("Disconnected Successfully");
                Log.d(TAG, "onSuccess: Remove Group");
            }

            @Override
            public void onFailure(int i) {
                connectionStatus.setText("Failed to Disconnect " + i);
            }
        });
    }

    private void initialWork() {
        btnOnOff = findViewById(R.id.onOff);
        spinDestDevice = findViewById(R.id.destDevice);
        btnSend = findViewById(R.id.sendButton);
        btnDisconnect = findViewById(R.id.disconnect);
        read_msg_box = findViewById(R.id.readMsg);
        connectionStatus = findViewById(R.id.connectionStatus);
        writeMsg = findViewById(R.id.writeMsg);
        forwardSwitch = findViewById(R.id.forwardSwitch);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.devices, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinDestDevice.setAdapter(adapter);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(this, getMainLooper(), null);

        mReceiver = new WiFiDirectBroadcastReceiver(mManager, mChannel, this);

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        sending = false;
    }

    WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {
            if (!peerList.getDeviceList().equals(peers)) {
                peers.clear();
                peers.addAll(peerList.getDeviceList());

                deviceNameArray = new String[peerList.getDeviceList().size()];
                deviceArray = new WifiP2pDevice[peerList.getDeviceList().size()];
                int index = 0;

                for (WifiP2pDevice device : peerList.getDeviceList()) {
                    deviceNameArray[index] = device.deviceName;
                    deviceArray[index] = device;
                    Log.d(TAG, "onPeersAvailable: Device Name: " + device.deviceName);
                    Log.d(TAG, "onPeersAvailable: Device MAC: " + device.deviceAddress);
                    index++;
                }

                waiting = false;
            }

            if (peers.size() == 0) {
                Toast.makeText(getApplicationContext(), "No Device Found", Toast.LENGTH_SHORT).show();
                return;
            }
        }
    };

    WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            final InetAddress groupOwnerAddress = wifiP2pInfo.groupOwnerAddress;

            if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
                connectionStatus.setText("Host");
                serverClass = new ServerClass();
                serverClass.start();

                Toast.makeText(getApplicationContext(), "I'm a real bo- Server!", Toast.LENGTH_SHORT).show();
            } else if (wifiP2pInfo.groupFormed) {
                connectionStatus.setText("Client");
                clientClass = new ClientClass(groupOwnerAddress);
                clientClass.start();


                Toast.makeText(getApplicationContext(), "I'm a real bo- Client!", Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mReceiver, mIntentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    public class ServerClass extends Thread {
        Socket socket;
        ServerSocket serverSocket;

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(8888);
                socket = serverSocket.accept();
                sendReceive = new SendReceive(socket);
                sendReceive.start();
                Log.d(TAG, "run: Before closing serverSocket");
                serverSocket.close();
                Log.d(TAG, "run: After closing serverSocket");
                Log.d(TAG, "run: ServerSocket isClosed: " + serverSocket.isClosed());
                serverSocket = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class SendReceive extends Thread {
        private Socket socket;
        private InputStream inputStream;
        private OutputStream outputStream;

        public SendReceive(Socket skt) {
            Log.d(TAG, "SendReceive: constructor");
            socket = skt;
            try {
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {

            byte[] buffer = new byte[1024];
            int bytes;

            while (socket != null) {
                Log.d(TAG, "run: Socket isClosed: " + socket.isClosed());

                try {
                    if (sending) {
                        Log.d(TAG, "run: A");
                        sending = false;

                        Thread thread = new Thread(new Runnable() {

                            @Override
                            public void run() {
                                write(currentMessage);
                            }
                        });

                        thread.start();

                        continue;
                    }

                    bytes = inputStream.read(buffer);

                    if (bytes > 0) {
                        Log.d(TAG, "run: B");
                        disconnect();
                        try {
                            socket.close();
                            socket = null;
                            if (serverClass != null && serverClass.socket != null) {
                                serverClass.socket.close();
                                serverClass.socket = null;
                            }
                            if (clientClass != null && clientClass.socket != null) {
                                clientClass.socket.close();
                                clientClass.socket = null;
                            }
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }

                        String[] decode = (new String(buffer)).split(",");

                        int ttl = Integer.parseInt(decode[0]);
                        String destAddress = decode[1];
                        int mssgLength = Integer.parseInt(decode[2]);
                        String message = decode[3];

                        ttl--;

                        if (destAddress.equals(currentDevice.deviceAddress)) {
                            Log.d(TAG, "run: C");
                            Log.d(TAG, "run: Message: " + message.substring(0, mssgLength));
                            handler.obtainMessage(MESSAGE_READ, bytes, -1, message.substring(0, mssgLength).getBytes()).sendToTarget();
                        } else if (ttl > 0) {
                            Log.d(TAG, "run: D");
                            Log.d(TAG, "run: Message: " + message.substring(0, mssgLength));
                            currentMessage = (ttl + "," + destAddress + "," + mssgLength + "," + message).getBytes();
                            //currentMessage = buffer;
                            sending = true;
                            Log.d(TAG, "run: DestAddress " + destAddress);
                            establishConnection(destAddress);
                        } else {
                            // TTL = 0, message lost
                            Log.d(TAG, "run: TTL = 0, Message Lost");
                        }
                    } else if (bytes < 0) {
                        Log.d(TAG, "run: E");
                        disconnect();
                        try {
                            socket.close();
                            socket = null;
                            if (serverClass != null && serverClass.socket != null) {
                                serverClass.socket.close();
                                serverClass.socket = null;
                            }
                            if (clientClass != null && clientClass.socket != null) {
                                clientClass.socket.close();
                                clientClass.socket = null;
                            }
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.d(TAG, "run: F");

                    disconnect();
                    try {
                        socket.close();
                        socket = null;
                        if (serverClass != null && serverClass.socket != null) {
                            serverClass.socket.close();
                            serverClass.socket = null;
                        }
                        if (clientClass != null && clientClass.socket != null) {
                            clientClass.socket.close();
                            clientClass.socket = null;
                        }
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                Log.d(TAG, "write: before");
                outputStream.write(bytes);
                Log.d(TAG, "write: after");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public class ClientClass extends Thread {
        Socket socket;
        String hostAdd;

        public ClientClass(InetAddress hostAddress) {
            hostAdd = hostAddress.getHostAddress();
            socket = new Socket();
        }

        @Override
        public void run() {
            try {
                socket.connect(new InetSocketAddress(hostAdd, 8888), 5000);
                Log.d(TAG, "run: Client connect socket Port: " + socket.getPort() + " " + socket.getLocalPort());
                sendReceive = new SendReceive(socket);
                sendReceive.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


    }
}