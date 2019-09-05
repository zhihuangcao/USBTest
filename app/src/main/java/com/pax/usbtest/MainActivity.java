package com.pax.usbtest;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.felhr.utils.HexData;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    static String TAG = "USBTest";

    UsbManager usbManager;
    TextView debug;
    private Context mContext;
    private static final String NETWORKSTATE_PATH = Environment
            .getExternalStorageDirectory() + "/usb_test.txt";

    private Map<String, Boolean> permissionMap = Collections.synchronizedMap(new HashMap<String, Boolean>());
    private BroadcastReceiver receiver;
    private BroadcastReceiver permissionHandler;
    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private Button mButton;
    UsbSerialDevice serdev;
    boolean serialStatus = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        verifyStoragePermissions(this);

        DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        debug = (TextView) findViewById(R.id.debug);
        debug.setMovementMethod(ScrollingMovementMethod.getInstance());

        mContext = getApplicationContext();
        usbManager = (UsbManager) getApplicationContext().getSystemService(Context.USB_SERVICE);

        receiver = new USBReceiver();
        permissionHandler = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (getClass().getName().equals(intent.getAction())) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                    permissionMap.put(device.getDeviceName(), granted);
                    Log.i(TAG, "Device granted:" + device.getDeviceName());

                    synchronized (device) {
                        device.notifyAll();
                    }
                }
            }
        };
        mButton = (Button) findViewById(R.id.button);
        mButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View arg0) {
                refresh();

            }
        });
    }

    /**
     * 显示键盘
     *
     * @param context
     * @param view
     */
    public static void showInputMethod(Context context, View view) {
        InputMethodManager im = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        im.showSoftInput(view, 0);
    }

    //隐藏虚拟键盘
    public static void HideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm.isActive()) {
            imm.hideSoftInputFromWindow(v.getApplicationWindowToken(), 0);
        }
    }

    /**
     * Checks if the app has permission to write to device storage
     * If the app does not has permission then the user will be prompted to
     * grant permissions
     *
     * @param activity
     */
    public static void verifyStoragePermissions(Activity activity) {

        try {
            int permission = ActivityCompat.checkSelfPermission(activity,
                    "android.permission.WRITE_EXTERNAL_STORAGE");
            if (permission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 发送指令
     *
     * @param view
     */
    public void send(View view) {
        if (serdev != null && serialStatus) {
            byte[] cmd = {0x7e, 0x00, 0x08, (byte) 0xb0, 0x53, 0x54, 0x41, 0x54, 0x5d, 0x49};
            serdev.write(cmd);
            debug.append("发送：" + HexData.bytes2Hex(cmd) + "\n");
        } else {
            Toast.makeText(this, "error", Toast.LENGTH_SHORT).show();
        }
    }

    class USBReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshAsync();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        getApplicationContext().registerReceiver(receiver, filter);
        getApplicationContext().registerReceiver(permissionHandler, new IntentFilter(permissionHandler.getClass().getName()));

        refreshAsync();
    }

    public void refreshAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();

        getApplicationContext().unregisterReceiver(receiver);
        getApplicationContext().unregisterReceiver(permissionHandler);
    }

    private void refresh() {
        Log.i(TAG, "Refreshing...");
        final StringBuilder str = new StringBuilder();

        str.append("Updated:" + new Date().toString() + "\n\n");

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            StringBuilder deviceStr = new StringBuilder();
            try {
                // Open and close device
                if (!usbManager.hasPermission(device)) {
                    usbManager.requestPermission(device, PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(permissionHandler.getClass().getName()),
                            PendingIntent.FLAG_UPDATE_CURRENT));

                    long giveUpTime = System.currentTimeMillis() + 2000;
                    Boolean granted = null;

                    synchronized (device) {
                        while (System.currentTimeMillis() < giveUpTime && granted == null) {
                            device.wait(2000);
                            granted = permissionMap.get(device.getDeviceName());
                        }
                    }

                    if (granted == null) {
                        throw new RuntimeException("Connection to USB device failed: User did not grant permission within 5 seconds.");
                    } else if (!granted) {
                        throw new RuntimeException("Connection to USB device failed: User denied permission to device.");
                    }
                }
                synchronized (this) {
                    for (int i = 0; i < device.getInterfaceCount(); ++i) {
                        final UsbInterface iface = device.getInterface(i);

                        try {
                            Log.i(TAG, "device.getDeviceId()=" + device.getDeviceId() + " iface.getId()=" + iface.getId() + " iface" + iface
                                    + " iface.getInterfaceClass=" + iface.getInterfaceClass());

                            if (isSupportedSerialPort(device, iface)) {

                                final UsbDeviceConnection connection = usbManager.openDevice(device);
//                                UsbSerialDevice serdev;

                                deviceStr.append("\n\n" + "   Serial interface: " + iface.toString());

                                // Claim interface and start reading from port
                                serdev = UsbSerialDevice.createUsbSerialDevice(device, connection, iface.getId());

                                if (serdev.open()) {
                                    serialStatus = true;
                                    serdev.setBaudRate(38400);
                                    //serdev.setBaudRate(115200);
                                    serdev.setDataBits(UsbSerialInterface.DATA_BITS_8);
                                    serdev.setParity(UsbSerialInterface.PARITY_NONE);
                                    serdev.setStopBits(UsbSerialInterface.STOP_BITS_1);

                                    serdev.read(new UsbSerialInterface.UsbReadCallback() {
                                        @Override
                                        public void onReceivedData(final byte[] data) {
                                            if (data != null && data.length > 0) {
                                                runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        debug.append("接收：" + HexData.bytes2Hex(data) + "\n");
                                                    }
                                                });
                                                Log.i(TAG, "data:" + new String(data));
                                            }
                                        }
                                    });
                                    //serdev.write("123456789012345".getBytes());
                                    //serdev.write("AT+CFUN=4".getBytes());
                                    SystemClock.sleep(1000);

                                } else {
                                    Log.i(TAG, "serdev closed");
                                }
                            } else if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                                deviceStr.append("\n\n" + "   HID interface: " + iface.toString());
                            } else if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                                //deviceStr.append("\n\n" + "   Printer interface: " + iface.toString());
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

            } catch (Exception ex) {
                str.append("\n\n   " + ex.toString());
                ex.printStackTrace();
            }
            if (deviceStr.length() > 0) {
                str.append("Device: " + device.toString());
                str.append(deviceStr);
                str.append("\n\n");
                device = null;
            }

        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                debug.setText(str.toString());
                Log.i("USBTest", str.toString());
            }
        });
    }

    private static String serialDeviceVendor(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();

        return com.felhr.deviceids.FTDISioIds.isDeviceSupported(vid, pid) ? "FTDI"
                : com.felhr.deviceids.CP210xIds.isDeviceSupported(vid, pid) ? "Silicon Labs"
                : com.felhr.deviceids.PL2303Ids.isDeviceSupported(vid, pid) ? "Prolific"
                : com.felhr.usbserial.UsbSerialDevice.isCdcDevice(device) ? "Generic"
                : com.felhr.deviceids.CH34xIds.isDeviceSupported(vid, pid) ? "Silicon Labs"
                : null;
    }

    private static boolean isSupportedSerialPort(UsbDevice device, UsbInterface iface) {
        if (serialDeviceVendor(device) == null) {
            return false;
        }

        if (com.felhr.usbserial.UsbSerialDevice.isCdcDevice(device) && !com.felhr.usbserial.UsbSerialDevice.isCdcInterface(iface)) {
            return false;
        }

        return true;
    }

    /**
     * 将数据存到文件中
     *
     * @param context  context
     * @param data     需要保存的数据
     * @param fileName 文件名
     */
    public void saveDataToFile(Context context, String data, String fileName) {
        FileOutputStream fileOutputStream = null;
        BufferedWriter bufferedWriter = null;
        Log.i("lhb", "fileName=" + fileName);
        try {
            /**
             * "data"为文件名,MODE_PRIVATE表示如果存在同名文件则覆盖，
             * 还有一个MODE_APPEND表示如果存在同名文件则会往里面追加内容
             */
            //fileOutputStream = context.openFileOutput(file.getAbsolutePath(),
            //		Context.MODE_APPEND);

            File file = new File(fileName);
            if (!file.exists()) {
                file.createNewFile();
            }
            fileOutputStream = new FileOutputStream(file, true);

            bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                    fileOutputStream));
            bufferedWriter.write(data);
            bufferedWriter.flush();
            bufferedWriter.close();

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (bufferedWriter != null) {
                    bufferedWriter.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
