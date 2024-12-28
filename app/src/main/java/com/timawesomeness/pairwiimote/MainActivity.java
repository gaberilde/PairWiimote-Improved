package com.timawesomeness.pairwiimote;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback {
    private BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    private RecyclerView recyclerView;
    private ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private ControllerType controllerType = ControllerType.WIIMOTE;
    private String localMAC = "";
    TextView instructions;
    static boolean pair = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        instructions = findViewById(R.id.instructions);
        // if we don't have bluetooth, quit
        if (adapter == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.no_bluetooth)
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel, (d, i) -> {
                        finishAffinity();
                    })
                    .show();
        } else {
            tryLocationPermission();

            if (!adapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1);
            }

            recyclerView = findViewById(R.id.list);
            recyclerView.setHasFixedSize(true);
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
            recyclerView.setAdapter(new BluetoothDeviceAdapter(devices));

            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(receiver, filter);
            IntentFilter filter2 = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
            registerReceiver(receiver, filter2);
            IntentFilter filter3 = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            registerReceiver(receiver, filter3);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                adapter.startDiscovery();
            }
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device.getName() != null && device.getName().contains("Nintendo RVL-")) {
                    devices.add(device);
                    ((BluetoothDeviceAdapter) recyclerView.getAdapter()).notifyNewItem();
                }
            } else if (action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST) && pair) {
                pair = false;
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                try {
                    byte[] pin = new byte[6];
                    for (int i = 0; i < 6; i++) {
                        switch (controllerType) {
                            case WIIMOTE:
                                // Wiimote pins are the remote's bluetooth MAC address reversed
                                // https://wiibrew.org/wiki/Wiimote#Bluetooth_Pairing
                                pin[i] = Integer.decode("0x" + device.getAddress().split(":")[5 - i]).byteValue();
                                break;
                            case WIIUPRO:
                                // Wii U Pro Controller pins seem to be the host's MAC address
                                pin[i] = Integer.decode("0x" + localMAC.split(":")[i]).byteValue();
                                break;
                            case WIISYNC:
                                // Wiimote is the host's MAC address reversed
                                pin[i] = Integer.decode("0x" + localMAC.split(":")[5 - i]).byteValue();
                                break;
                        }
                    }
                    Toast.makeText(context, "Pairing...", Toast.LENGTH_SHORT).show();
                    device.setPin(pin);
                    device.getClass().getMethod("setPairingConfirmation", boolean.class).invoke(device, true);
                } catch (Exception ignored) {
                }
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                findViewById(R.id.progressBar).setVisibility(View.GONE);
            }
        }
    };

    /**
     * Check if we have location permissions (required for bluetooth scanning)
     */
    private void tryLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { // if we're pre-M, permissions are granted at install-time
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            tryLocationPermission();
        } else {
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adapter.cancelDiscovery();
        unregisterReceiver(receiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.app_bar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh:
                refresh();
                break;
            case R.id.wiimote:
                controllerType = ControllerType.WIIMOTE;
                instructions.setText(R.string.wiimote_instructions);
                break;
            case R.id.wiiupro:
                final EditText input = new EditText(this);
                input.setHint(R.string.local_mac_hint);
                input.setFilters(new InputFilter[] { (charSequence, start, end, spanned, sstart, send) -> {
                    for (int i = start; i < end; i++) {
                        if (!String.valueOf(charSequence.charAt(i)).matches("[0-9a-fA-F:]")) {
                            input.setError(getString(R.string.local_mac_error));
                            return "";
                        }
                    }
                    return null;
                } });
                input.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.local_mac_title)
                        .setMessage(R.string.local_mac_instructions)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                                localMAC = input.getText().toString();
                                controllerType = ControllerType.WIIUPRO;
                                instructions.setText(R.string.wiiupro_instructions);
                                Toast.makeText(this, "Set successfully", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> {
                            dialogInterface.cancel();
                        })
                        .show();
                break;
            case R.id.wiisync:
                final EditText input2 = new EditText(this);
                input2.setHint(R.string.local_mac_hint);
                input2.setFilters(new InputFilter[] { (charSequence, start, end, spanned, sstart, send) -> {
                    for (int i = start; i < end; i++) {
                        if (!String.valueOf(charSequence.charAt(i)).matches("[0-9a-fA-F:]")) {
                            input2.setError(getString(R.string.local_mac_error));
                            return "";
                        }
                    }
                    return null;
                } });
                input2.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.local_mac_title)
                        .setMessage(R.string.local_mac_instructions)
                        .setView(input2)
                        .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                            localMAC = input2.getText().toString();
                            controllerType = ControllerType.WIISYNC;
                            instructions.setText(R.string.wiiupro_instructions);
                            Toast.makeText(this, "Set successfully", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> {
                            dialogInterface.cancel();
                        })
                        .show();
                break;
        }
        return true;
    }

    /**
     * Refresh bluetooth devices
     */
    public void refresh() {
        adapter.cancelDiscovery();
        devices.clear();
        ((BluetoothDeviceAdapter) recyclerView.getAdapter()).notifyDataReset();
        findViewById(R.id.progressBar).setVisibility(View.VISIBLE);
        adapter.startDiscovery();
    }

    private enum ControllerType {
        WIIUPRO, WIIMOTE, WIISYNC
    }
}